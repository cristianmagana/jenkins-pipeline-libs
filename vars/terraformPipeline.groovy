#!/usr/bin/groovy

def call(body) {
    // evaluate the body block, set vars, and collect configuration into the object
    def pipelineParams = [:]
    def buildOpts = ''
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
        agent {label 'terraform && !windows'}
        options {
            buildDiscarder(
                    logRotator(
                            artifactDaysToKeepStr: '',
                            artifactNumToKeepStr: '3',
                            daysToKeepStr: '',
                            numToKeepStr: '30'
                    )
            )
        }

        parameters {
            choice(
                    name: 'ENVIRONMENT',
                    choices: 'sbox\ndev\nqa\nstg\nprod\n',
                    description: 'The environment in which to deploy the Terraform stack.')
            choice(
                    name: 'ACTION',
                    choices: pipelineParams.TF_LOG ?: 'plan\ndeploy\ndestroy',
                    description: 'The Terraform action you would like to perform.')
            string(
                    name: 'AWS_DEFAULT_REGION',
                    defaultValue: pipelineParams.AWS_DEFAULT_REGION ?: 'us-east-1',
                    description: 'The AWS region in which to deploy the Terraform stack.')
            choice(
                    name: 'TF_LOG',
                    choices: pipelineParams.TF_LOG ?: 'INFO\nTRACE\nDEBUG\nWARN\nERROR',
                    description: 'The logging level to use for Terraform.')
            string(
                    name: 'NOTIFY_CHANNELS',
                    defaultValue: pipelineParams.NOTIFY_CHANNELS ?: '',
                    description: 'Slack channel(s) to notify of build results.')
            string(
                    name: 'BUILD_SCRIPTS_DIR',
                    defaultValue: pipelineParams.BUILD_SCRIPTS_DIR ?: 'build-scripts',
                    description: 'The directory in which to clone the build scripts.')
            string(
                    name: 'BUILD_SCRIPTS_REPO',
                    defaultValue: pipelineParams.BUILD_SCRIPTS_REPO ?: 'git@github.com.com:digitalxtian/build-scripts.git',
                    description: 'The GitHub URL to the build-scripts repo.')
            string(
                    name: 'BUILD_SCRIPTS_BRANCH_OR_TAG',
                    defaultValue: pipelineParams.BUILD_SCRIPTS_BRANCH_OR_TAG ?: 'v1.0.34-493ba8cf',
                    description: 'The branch, tag, or commit hash to use when checking out the build-scripts repo.')
        }
        stages {
            stage('Checkout project') {
                steps {
                    gitCheckoutProject {}
                    script {
                        // Set display name
                        currentTag = getCurrentTag().trim()
                        buildSuffix = isMaster() ? '' : '-SNAPSHOT'
                        commitHash = getCurrentCommitHash().trim()
                        currentBuild.displayName = getBuildName(env.BUILD_NUMBER, getNextTag(currentTag, buildSuffix))

                        // Generate notify message for later use. The extra spacing helps formatting in Slack.
                        notify_msg =
"""
*${env.JOB_NAME}*
*Build*:    <${env.BUILD_URL}|${currentBuild.displayName}>
*Repo*:    <${scm.getUserRemoteConfigs()[0].getUrl()}|${getRepoFromURL(scm.getUserRemoteConfigs()[0].getUrl())}>
*Branch*: ${env.BRANCH_NAME}
*Changes*:
${( getChangeString() ?: "None" )}
""".stripIndent()
                    }
                }
            }
            stage('Checkout build scripts') {
                steps {
                    gitCheckoutBuildScripts(params.BUILD_SCRIPTS_DIR, params.BUILD_SCRIPTS_REPO, params.BUILD_SCRIPTS_BRANCH_OR_TAG)
                }
            }
            stage('Create Terraform Plan') {
                steps {
                    // Use AWS credentials for packer and GHE credentials for tagging
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: getAwsCredentialId("sa_${params.ENVIRONMENT}")],
                                     sshUserPrivateKey(
                                             credentialsId: getGitCredentialId(),
                                             keyFileVariable: 'SSH_PRIVATE_KEY_PATH')]) {
                        // Assemble build options
                        script {
                            buildOpts = params.ENVIRONMENT           ? buildOpts + " -e ${params.ENVIRONMENT}"          : buildOpts
                            buildOpts = params.AWS_DEFAULT_REGION    ? buildOpts + " -r ${params.AWS_DEFAULT_REGION}"   : buildOpts
                            buildOpts = params.TF_LOG                ? buildOpts + " -L ${params.TF_LOG}"               : buildOpts
                            buildOpts = SSH_PRIVATE_KEY_PATH         ? buildOpts + " -k ${SSH_PRIVATE_KEY_PATH}"        : buildOpts
                            buildOpts = (params.ACTION == 'destroy') ? buildOpts + " -D"                                : buildOpts
                            buildOpts = isMaster()                   ? buildOpts + " -R"                                : buildOpts
                            buildOpts = buildOpts + " -p"
                        }

                        // Execute and honor ansi coloring from Terraform
                        ansiColor('xterm') {
                            echo 'Executing deploy script...'
                            sh "./${params.BUILD_SCRIPTS_DIR}/terraform/deploy.sh ${buildOpts}"
                        }
                    }
                }
            }
            stage('Execute Terraform Stack') {
                when {
                    expression { return (params.ACTION != 'plan' && (isMaster() || params.ENVIRONMENT == 'sbox')) }
                }
                steps {
                    // Approval confirmation before continuing
                    input message: 'Do you wish to continue?', ok: 'Continue'

                    // Use AWS credentials for packer and GHE credentials for tagging
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: getAwsCredentialId("sa_${params.ENVIRONMENT}")],
                                     sshUserPrivateKey(
                                             credentialsId: 'GH-Service-Account-SSH',
                                             keyFileVariable: 'SSH_PRIVATE_KEY_PATH')]) {
                        // Assemble build options
                        script {
                            buildOpts = ''
                            buildOpts = params.ENVIRONMENT           ? buildOpts + " -e ${params.ENVIRONMENT}"          : buildOpts
                            buildOpts = params.AWS_DEFAULT_REGION    ? buildOpts + " -r ${params.AWS_DEFAULT_REGION}"   : buildOpts
                            buildOpts = params.TF_LOG                ? buildOpts + " -L ${params.TF_LOG}"               : buildOpts
                            buildOpts = SSH_PRIVATE_KEY_PATH         ? buildOpts + " -k ${SSH_PRIVATE_KEY_PATH}"        : buildOpts
                            buildOpts = (params.ACTION == 'deploy')  ? buildOpts + " -d"                                : buildOpts
                            buildOpts = (params.ACTION == 'destroy') ? buildOpts + " -D"                                : buildOpts
                        }

                        // Execute and honor ansi coloring from Terraform
                        ansiColor('xterm') {
                            echo 'Executing deploy script...'
                            sh "./${params.BUILD_SCRIPTS_DIR}/terraform/deploy.sh ${buildOpts}"
                        }
                    }
                }
            }
        }
        post {
            success {
                slackSend channel: "${params.NOTIFY_CHANNELS}",
                          color: 'good',
                          message: "${notify_msg}"
            }
            failure {
                slackSend channel: "${params.NOTIFY_CHANNELS}",
                          color: 'danger',
                          message: "${notify_msg}"
            }
            unstable {
                slackSend channel: "${params.NOTIFY_CHANNELS}",
                          color: 'warning',
                          message: "${notify_msg}"
            }
        }
    }
}
