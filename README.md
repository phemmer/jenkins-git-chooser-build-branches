This is a Jenkins plugin which provides an additional build branch choosing strategy to the [git plugin](https://wiki.jenkins-ci.org/display/JENKINS/Git+Plugin). It results in Jenkins building a commit on each branch that the Jenkins job has been configured to monitor.
The default behavior is such that if a commit is seen on multiple branches, that commit will only be built once. If your build job has different behavior based on which branch is being built from, this may not be the desired behavior.

To use:

1. Install the plugin into Jenkins.
2. In the job configuration, under 'Source Code Management' -> 'Additional Behaviors', select the 'Add' dropdown and 'Strategy for choosing what to build'.
3. In the new 'Choosing strategy', select 'Build branches'.
