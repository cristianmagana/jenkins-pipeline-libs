# jenkins-pipeline-libs

Libraries to help keep Jenkins' builds [DRY](http://en.wikipedia.org/wiki/Don't_repeat_yourself).

## How to Use This Library
The master branch of this library is implicitly loaded whenever using a `Jenkinsfile`.

If you wish to lock your project to a specific tag or branch of this global library, that can be done like so:
```groovy
/* Using a version specifier, such as branch, tag, etc */
@Library('jenkins-pipeline-libs@1.0') _
/* Using the latest library from branch_name */
@Library('jenkins-pipeline-libs@branch_name') _
```

## Pipelines
A pipeline is a generic Jenkinsfile workflow with a set of steps which encapsulate a common build, test, and/or release pattern.
Instead of using individual functions within your project's custom pipeline, a workflow could simply
replace it. Pipelines in this project have the name "Pipeline" in their file name.

### Pipeline Arguments
Each pipeline has a different set of parameters to which you may pass arguments.
This allows you to optionally customize how a pipeline runs for your project.

**All pipeline arguments are optional, and when omitted a default value will be supplied.**

**If you only use the default values for each argument, your `Jenkinsfile` may be only a single line long.**