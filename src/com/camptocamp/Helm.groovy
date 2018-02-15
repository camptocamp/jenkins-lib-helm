#!/usr/bin/groovy
package com.camptocamp;

def helmNamespace(Map args) {
    def String namespace

    // If namespace isn't parsed into the function set the namespace to the name
    if (args.namespace == null) {
        namespace = args.name
    } else {
        namespace = args.namespace
    }
    println "using namespace '${namespace}'"
    return namespace  
}

def helmTillerNamespace(Map args) {
    def String tiller_namespace
    // If tiller_namespace isn't parsed into the function set the tiller_namespace to kube-system
    if (args.tiller_namespace == null) {
        tiller_namespace = "kube-system"
    } else {
        tiller_namespace = args.tiller_namespace
    }
    println "using tiller namespace '${tiller_namespace}'"
    return tiller_namespace     
}

def helmLint(String chart_dir) {
    // lint helm chart
    println "running helm lint ${chart_dir}"
    sh "helm lint ${chart_dir}"
}

def helmVersion(Map args) {
    // show versions
    def tiller_namespace = helmTillerNamespace(args)
    println "checking client/server version"
    sh "helm version --tiller-namespace=${tiller_namespace}"
}

def helmList(Map args) {
    def tiller_namespace = helmTillerNamespace(args)
    // list releases
    sh "helm list --tiller-namespace=${tiller_namespace}"
}

def helmInit(Map args) {
    // setup helm connectivity to Kubernetes API and Tiller
    def tiller_namespace = helmTillerNamespace(args)
    println "initiliazing helm client"
    sh "helm init --client-only --tiller-namespace=${tiller_namespace}"
}

def helmDeploy(Map args) {
    def values_map = []
    def String values
    def String values_file

    def namespace = helmNamespace(args)
    def tiller_namespace = helmTillerNamespace(args)

    if (args.containsKey("values_file")) {
        values_file = "-f ${args.values_file}"
    } else {
        values_file = ""
    }

    if (args.containsKey("values")) {
        for ( item in args.values ) {
            values_map.add("$item.key=$item.value")
        }
        values = "--set ${values_map.join(',')}"
    } else {
        values = ""
    }

    if (args.dry_run) {
        println "Running dry-run deployment"

        sh "helm upgrade --dry-run --install ${args.name} ${args.chart_dir} ${values_file} ${values} --namespace=${namespace} --tiller-namespace=${tiller_namespace}"
    } else {
        println "Running deployment"

        sh "helm upgrade --wait --install ${args.name} ${args.chart_dir} ${values_file} ${values} --namespace=${namespace} --tiller-namespace=${tiller_namespace}"

        echo "Application ${args.name} successfully deployed. Use helm status ${args.name} to check"
    }
}

def helmDelete(Map args) {
    def namespace = helmNamespace(args)
    def tiller_namespace = helmTillerNamespace(args)
    println "Running helm delete ${args.name}"
    sh "helm delete ${args.name} --tiller-namespace=${tiller_namespace}"
}

def helmTest(Map args) {
    def namespace = helmNamespace(args)
    def tiller_namespace = helmTillerNamespace(args)
    println "Running Helm test"
    sh "helm test ${args.name} --cleanup --tiller-namespace=${tiller_namespace}"
}

def addDependencyRepos(Map args) {
    // dynamically add helm repository based on the requirements.yaml file
    // because the repo has to exist for dependencies update
    def depsFile = "${args.chart_dir}/requirements.yaml"
    if (fileExists(depsFile)) {
        def deps = readYaml file: "${args.chart_dir}/requirements.yaml"
        def repos = []
        deps['dependencies'].eachWithIndex { dep, index ->
            if (!repos.includes(dep['repository'])) {
                helmAddRepo(
                    name        : repository_${index},
                    repository  : dep['repository']
                )
            }
            repos.add(dep.repository)
        }
    }
}

def helmAddRepo(Map args) {
    println "Adding repository ${args.name} -> ${args.repository}"
    sh "helm repo add ${args.name} ${args.repository}"
}

def helmUpdateDependencies(Map args) {
    addDependencyRepos(args)
    println "Updating Helm dependencies"
    sh "helm dependency update ${args.chart_dir}"
}

def helmListDependencies(Map args) {
    println "Updating Helm dependencies"
    sh "helm dependency list ${args.chart_dir}"
}

def gitEnvVars() {
    // create git envvars
    println "Setting envvars to tag container"

    sh 'git rev-parse HEAD > git_commit_id.txt'
    try {
        env.GIT_COMMIT_ID = readFile('git_commit_id.txt').trim()
        env.GIT_SHA = env.GIT_COMMIT_ID.substring(0, 7)
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_COMMIT_ID ==> ${env.GIT_COMMIT_ID}"

    // workaround missing branch name in env
    def jobName = env.JOB_NAME
    def branch = jobName.tokenize( '-' ).last()
    env.BRANCH = branch
    println "env.BRANCH ==> ${branch}"
}

def containerBuildPub(Map args) {

    println "Running Docker build/publish: ${args.host}/${args.acct}/${args.repo}:${args.tags}"

    docker.withRegistry("https://${args.host}", "${args.auth_id}") {

        // def img = docker.build("${args.acct}/${args.repo}", args.dockerfile)
        def img = docker.image("${args.acct}/${args.repo}")
        sh "docker build --build-arg VCS_REF=${env.GIT_SHA} --build-arg BUILD_DATE=`date -u +'%Y-%m-%dT%H:%M:%SZ'` -t ${args.acct}/${args.repo} ${args.dockerfile}"
        for (int i = 0; i < args.tags.size(); i++) {
            img.push(args.tags.get(i))
        }

        return img.id
    }
}

def getContainerTags(Map tags = [:]) {

    println "getting list of tags for container"
    def String commit_tag
    def String version_tag

    try {
        // if PR branch tag with only branch name
        if (env.BRANCH_NAME.contains('PR')) {
            commit_tag = env.BRANCH_NAME
            tags << ['commit': commit_tag]
            return tags
        }
    } catch (Exception e) {
        println "WARNING: commit unavailable from env. ${e}"
    }

    // commit tag
    try {
        // if branch available, use as prefix, otherwise only commit hash
        if (env.BRANCH_NAME) {
            commit_tag = env.BRANCH_NAME + '-' + env.GIT_COMMIT_ID.substring(0, 7)
        } else {
            commit_tag = env.GIT_COMMIT_ID.substring(0, 7)
        }
        tags << ['commit': commit_tag]
    } catch (Exception e) {
        println "WARNING: commit unavailable from env. ${e}"
    }

    // master tag
    try {
        if (env.BRANCH_NAME == 'master') {
            tags << ['master': 'latest']
        }
    } catch (Exception e) {
        println "WARNING: branch unavailable from env. ${e}"
    }

    // build tag only if none of the above are available
    if (!tags) {
        try {
            tags << ['build': env.BUILD_TAG]
        } catch (Exception e) {
            println "WARNING: build tag unavailable from config.project. ${e}"
        }
    }

    return tags
}

def getContainerRepoAcct(config) {

    println "setting container registry creds according to Jenkinsfile.json"
    def String acct

    if (env.BRANCH_NAME == 'master') {
        acct = config.container_repo.master_acct
    } else {
        acct = config.container_repo.alt_acct
    }

    return acct
}

@NonCPS
def getMapValues(Map map=[:]) {
    // jenkins and workflow restriction force this function instead of map.values(): https://issues.jenkins-ci.org/browse/JENKINS-27421
    def entries = []
    def map_values = []

    entries.addAll(map.entrySet())

    for (int i=0; i < entries.size(); i++){
        String value =  entries.get(i).value
        map_values.add(value)
    }

    return map_values
}