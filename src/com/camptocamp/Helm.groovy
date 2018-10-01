#!/usr/bin/groovy
package com.camptocamp;

// public void hieraTemplate(config=[:], body) {
//     def envVars = []
//     if (config.containsKey('secrets')){
//         envVars = getEnvVars(config.secrets)
//     }

//     podTemplate(
//         name: 'hiera',
//         label: 'hiera',
//         cloud: 'openshift',
//         serviceAccount: 'jenkins',
//         containers: [
//             containerTemplate(
//                 name: 'jnlp',
//                 image: "docker-registry.default.svc:5000/${env.NAMESPACE_PREFIX}-cicd/jenkins-slave-hiera:latest",
//                 ttyEnabled: true,
//                 command: '',
//                 privileged: false,
//                 alwaysPullImage: true,
//                 workingDir: '/tmp',
//                 args: '${computer.jnlpmac} ${computer.name}',
//                 envVars: envVars,
//             )
//         ],
//     ){
//         body()
//     }
// }

public void pipeline(config=[:], body) {
  properties([
    gitLabConnection('Gitlab'),
    pipelineTriggers([
      [
        $class: 'GitLabPushTrigger',
        branchFilterType: 'All',
        triggerOnPush: true,
        triggerOnMergeRequest: false,
        ciSkip: true,
        // you can add your trigger secret token here, or edit the pipeline job to generate one
        secretToken: gilab_secret_token
      ]
    ])
  ])

  podTemplate(
    label: 'jenkins-slave',
    cloud: 'openshift',
    serviceAccount: 'jenkins',
    containers: [
      containerTemplate(
        name: 'skopeo',
        image: "docker-registry.default.svc:5000/${namespace_prefix}-cicd/skopeo-jenkins-slave:latest",
        ttyEnabled: true,
        command: 'cat',
        alwaysPullImage: true,
      ),
    ],
    imagePullSecrets: ['ftth-openshift-pull-secret'],
  ){
    node() {

      // add bash environment variables
      def ENV = [:]
      def nodeEnvs = helm.getEnvMap()
      nodeEnvs.each { key, value ->
        ENV.put(key, value)
      }
      
      def debug = false
      def scmVars = checkout(scm)

      // get branch & commit from scm   
      ENV.GIT_BRANCH = scmVars.GIT_BRANCH
      ENV.GIT_COMMIT_SHA = scmVars.GIT_COMMIT.substring(0, 7)

      // get tags from library
      ENV.GIT_TAG_NAME = helm.gitTagName()
      ENV.GIT_TAG_MESSAGE = helm.gitTagMessage()

      // tag image with version, and branch-commit_id
      def dev_image_tag = "ref-${ENV.GIT_COMMIT_SHA}"
      def version_tag = ENV.GIT_TAG_NAME

      // Compute namespace & release name based on branch names
      def namespace_postfix = 'cicd'
      def release_name = "${release_base_name}"
      def values_release_name = release_name
      def image_tag = dev_image_tag

      switch(ENV.GIT_BRANCH) {
        case 'origin/master':
          namespace_postfix = "dev"
          break
        case 'origin/int':
          namespace_postfix = "int"
          image_tag = version_tag
          break
        case 'origin/prd':
          namespace_postfix = "prd"
          image_tag = version_tag
          break
        default:
          namespace_postfix = "dev"
          release_name = "${release_base_name}-${dev_image_tag}"
          break
      }
      def namespace = "${namespace_prefix}-${namespace_postfix}"

      // Debug
      if (debug) {

        scm.each { name, value -> 
          println "scm Name: $name -> Value $value"
        }

        scmVars.each { name, value -> 
          println "scmVars Name: $name -> Value $value"
        }
        ENV.each { name, value -> 
          println "ENV Name: $name -> Value $value"
        }
      }

      echo "ENV.GIT_BRANCH:       ${ENV.GIT_BRANCH}"
      echo "ENV.GIT_COMMIT_SHA:   ${ENV.GIT_COMMIT_SHA}"
      echo "ENV.GIT_TAG_NAME:     ${ENV.GIT_TAG_NAME}"
      echo "ENV.GIT_TAG_MESSAGE:  ${ENV.GIT_TAG_MESSAGE}"
      echo "namespace:            ${namespace}"
      echo "release_name:         ${release_name}"


      def stage_description = "${image_tag} from ${ENV.GIT_BRANCH} to ${namespace} (TODO manually untill release management)"
      // set stages to be synced with gitlab
      // gitlabBuilds(builds: [
      //   "build ${stage_description}",
      //   "deploy ${stage_description}",
      // ]) {
        // Only build if not int or prd branch
      if (!['origin/int','origin/prd'].contains(ENV.GIT_BRANCH)) {
        stage("build ${stage_description}"){
          // gitlabCommitStatus("build ${stage_description}") {
            // Build on same commit 

          openshiftBuild(
            buildConfig: build_config_name,
            commitID: ENV.GIT_COMMIT_SHA,
            showBuildLogs: 'true',
          )

          // tag remote image
          node('jenkins-slave'){
            container('skopeo'){
              withCredentials([usernameColonPassword(credentialsId: 'mtr-creds', variable: 'MTR_CREDS')]) {
                sh """
                  skopeo copy --src-creds ${MTR_CREDS} \
                  --dest-creds ${MTR_CREDS} \
                  docker://${registry}/${deploy_chart_name}:latest \
                  docker://${registry}/${deploy_chart_name}:${dev_image_tag}
                """
              }
            }
          }

          // Sync the pushed image with the imagestream
          sh """
            oc import-image ${deploy_chart_name} \
            -n ${namespace_prefix}-cicd \
            --from ${registry}/${deploy_chart_name} \
            --all \
            --confirm
          """

          // // Tag the last build with commit id
          // openshiftTag(
          //   srcStream: deploy_chart_name,
          //   srcTag: 'latest',
          //   destStream: deploy_chart_name,
          //   destTag: dev_image_tag
          // )
          // check the im agestream references

          sh """
            oc describe is ${deploy_chart_name} -n ${namespace_prefix}-cicd
          """
        }
        
      } else {

        stage("deploy ${stage_description}"){
          // gitlabCommitStatus("deploy ${stage_description}") {

          def Boolean promote
          promote = false

          if (['origin/prd'].contains(ENV.GIT_BRANCH)) {
            try {
              timeout(time: 7, unit: 'DAYS') {
                promote = input message: 'Input Required',
                parameters: [
                  [ $class: 'BooleanParameterDefinition',
                    defaultValue: true,
                    description: "Check the box to deploy on Production",
                    name: "deploy ${stage_description}"
                  ]
                ]
              }
            } catch (err) {
              // don't promote => no error
            }
          } else {
                      
            // tag remote image
            node('jenkins-slave'){
              container('skopeo'){
                withCredentials([string(credentialsId: 'mtr-creds', variable: 'MTR_CREDS')]) {
                  sh """
                    skopeo copy --src-creds ${MTR_CREDS} \
                    --dest-creds ${MTR_CREDS} \
                    docker://${registry}/${deploy_chart_name}:${dev_image_tag} \
                    docker://${registry}/${deploy_chart_name}:${version_tag}
                  """
                }
              }
            }

            // Sync the pushed image with the imagestream
            sh """
              oc import-image ${deploy_chart_name} \
              -n ${namespace_prefix}-cicd \
              --from ${registry}/${deploy_chart_name} \
              --all \
              --confirm
            """

            // check the imagestream references
            sh """
              oc describe is ${deploy_chart_name} -n ${namespace_prefix}-cicd
            """

          }
        }
      }

      stage("deploy ${stage_description}"){
        // gitlabCommitStatus("deploy ${stage_description}") {
        echo """
      # Manual deployment

      # Set location where you cloned the helm-charts GIT repository with path to your chart
      HELM_CHART_LOCATION=${helm_charts_location}

      # Set location where you cloned the helm-values GIT repository with path to your values (do not include the environment)
      HELM_VALUES_LOCATION=${helm_values_location}

      # Install/Upgrade the deployment
      helm upgrade ${release_name} \${HELM_CHART_LOCATION}/${deploy_chart_name} -i --tiller-namespace ${namespace} --namespace ${namespace} --set image.tag=${image_tag} -f \${HELM_VALUES_LOCATION}/${deploy_chart_name}/${cluster_name}/${namespace}/${values_release_name}/values.yaml

        """
        // }

        if (!['origin/master'].contains(ENV.GIT_BRANCH)) {
          // Delete deployment as tests passed
          echo """"
      # Once you're happy, you can delete the deployment of the feature branch with
      helm delete ${release_name} --tiller-namespace ${namespace}

      # If needed, the deleted feature branch release can be re-deployed with
      helm rollback ${release_name} 1 --tiller-namespace ${namespace}
            
          """
        }
      }
    }
  }
}

    

public void helmTemplate(config=[:], body) {

    def envVars = []
    if (config.containsKey('secrets')){
        envVars = getEnvVars(config.secrets)
    }

    def label = "helm-worker"
    podTemplate(
        name: 'helm-worker',
        label: 'helm-worker',
        cloud: 'openshift',
        serviceAccount: 'jenkins',
        containers: [
            containerTemplate(
                name: 'helm',
                image: "docker-registry.default.svc:5000/${env.NAMESPACE_PREFIX}-cicd/jenkins-slave-helm:latest",
                ttyEnabled: true,
                command: 'cat',
            ),
            containerTemplate(
                name: 'hiera',
                image: "docker-registry.default.svc:5000/${env.NAMESPACE_PREFIX}-cicd/jenkins-slave-hiera:latest",
                ttyEnabled: true,
                command: 'cat',
            )
        ],      
        envVars: envVars,
    ){
        body()
    }
}

def getEnvVars(secrets){
    def envVars = [
        envVar(
            key: 'JAVA_GC_OPTS',
            value: '-XX:+UseParallelGC -XX:MinHeapFreeRatio=20 -XX:MaxHeapFreeRatio=40 -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90 -XX:MaxMetaspaceSize=2g'
        )
    ]

    for (secret in secrets) {
        envVars.add(
            secretEnvVar(
                key: secret.key,
                secretName: secret.secretName,
                secretKey: secret.secretKey
            )
        )
    }
    return envVars
}

def getEnvMap(){
    bashEnvs = sh (
        script: 'env | sort',
        returnStdout: true
    ).split("\n")

    envMap = [:]

    for ( bashEnv in bashEnvs ) {
        if (bashEnv) {
            bashEnvMap = bashEnv.split("=")
            envMap.put(bashEnvMap[0].trim(), bashEnvMap[1].trim())
        }
    }

    return envMap
}


/** @return The tag name, or `null` if the current commit isn't a tag. */
String gitTagName() {
    commit = getCommit()
    if (commit) {
        desc = sh(script: "git describe --always --tags ${commit}", returnStdout: true)?.trim()
        if (isTag(desc)) {
            return desc
        }
    }
    return null
}

/** @return The tag message, or `null` if the current commit isn't a tag. */
String gitTagMessage() {
    name = gitTagName()
    msg = sh(script: "git tag -n10000 -l ${name}", returnStdout: true)?.trim()
    if (msg) {
        return msg.substring(name.size()+1, msg.size())
    }
    return null
}

String getCommit() {
    return sh(script: 'git rev-parse HEAD', returnStdout: true)?.trim()
}

@NonCPS
boolean isTag(String desc) {
    match = desc =~ /.+-[0-9]+-g[0-9A-Fa-f]{6,}$/
    result = !match
    match = null // prevent serialisation
    return result
}

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

        sh "helm upgrade --force --wait --install ${args.name} ${args.chart_dir} ${values_file} ${values} --namespace=${namespace} --tiller-namespace=${tiller_namespace}"

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
            if (!repos.contains(dep['repository'])) {
                helmAddRepo(
                    name        : "repository_${index}",
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