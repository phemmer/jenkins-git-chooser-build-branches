package hudson.plugins.git.chooser.buildBranches;

import hudson.Extension;
import hudson.EnvVars;
import hudson.model.TaskListener;
import hudson.plugins.git.*;
import hudson.plugins.git.util.GitUtils;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.plugins.git.util.BuildChooser;
import hudson.plugins.git.util.BuildChooserContext;
import hudson.plugins.git.util.BuildChooserDescriptor;
import hudson.plugins.git.util.CommitTimeComparator;
import hudson.remoting.VirtualChannel;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteConfig;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.RepositoryCallback;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

import static java.util.Collections.emptyList;

public class BuildBranchesChooser extends BuildChooser {

    /* Ignore symbolic default branch ref. */
    private static final BranchSpec HEAD = new BranchSpec("*/HEAD");

    @DataBoundConstructor
    public BuildBranchesChooser() {
    }

    /**
     * Determines which Revisions to build.
     *
     * If only one branch is chosen and only one repository is listed, then
     * just attempt to find the latest revision number for the chosen branch.
     *
     * If multiple branches are selected or the branches include wildcards, then
     * use the advanced usecase as defined in the getAdvancedCandidateRevisons
     * method.
     *
     * @throws IOException
     * @throws GitException
     */
    @Override
    public Collection<Revision> getCandidateRevisions(boolean isPollCall, String branchSpec,
                                                      GitClient git, TaskListener listener, BuildData data, BuildChooserContext context)
            throws GitException, IOException, InterruptedException {

        verbose(listener,"getCandidateRevisions({0},{1},,,{2}) considering branches to build",isPollCall,branchSpec,data);

        // if the branch name contains more wildcards then the simple usecase
        // does not apply and we need to skip to the advanced usecase
        if (branchSpec == null || branchSpec.contains("*"))
            return getAdvancedCandidateRevisions(isPollCall,listener, git ,data, context);

        // check if we're trying to build a specific commit
        // this only makes sense for a build, there is no
        // reason to poll for a commit
        if (!isPollCall && branchSpec.matches("[0-9a-f]{6,40}")) {
            try {
                ObjectId sha1 = git.revParse(branchSpec);
                Revision revision = new Revision(sha1);
                revision.getBranches().add(new Branch("detached", sha1));
                verbose(listener,"Will build the detached SHA1 {0}",sha1);
                return Collections.singletonList(revision);
            } catch (GitException e) {
                // revision does not exist, may still be a branch
                // for example a branch called "badface" would show up here
                verbose(listener, "Not a valid SHA1 {0}", branchSpec);
            }
        }

        Collection<Revision> revisions = new ArrayList<Revision>();

        // if it doesn't contain '/' then it could be an unqualified branch
        if (!branchSpec.contains("/")) {

            // <tt>BRANCH</tt> is recognized as a shorthand of <tt>*/BRANCH</tt>
            // so check all remotes to fully qualify this branch spec
            for (RemoteConfig config : gitSCM.getRepositories()) {
                String repository = config.getName();
                String fqbn = repository + "/" + branchSpec;
                verbose(listener, "Qualifying {0} as a branch in repository {1} -> {2}", branchSpec, repository, fqbn);
                revisions.addAll(getHeadRevision(isPollCall, fqbn, git, listener, data));
            }
        } else {
            // either the branch is qualified (first part should match a valid remote)
            // or it is still unqualified, but the branch name contains a '/'
            List<String> possibleQualifiedBranches = new ArrayList<String>();
            for (RemoteConfig config : gitSCM.getRepositories()) {
                String repository = config.getName();
                String fqbn;
                if (branchSpec.startsWith(repository + "/")) {
                    fqbn = "refs/remotes/" + branchSpec;
                } else if(branchSpec.startsWith("remotes/" + repository + "/")) {
                    fqbn = "refs/" + branchSpec;
                } else if(branchSpec.startsWith("refs/heads/")) {
                    fqbn = "refs/remotes/" + repository + "/" + branchSpec.substring("refs/heads/".length());
                } else {
                    //Try branchSpec as it is - e.g. "refs/tags/mytag"
                    fqbn = branchSpec;
                }
                verbose(listener, "Qualifying {0} as a branch in repository {1} -> {2}", branchSpec, repository, fqbn);
                possibleQualifiedBranches.add(fqbn);

                //Check if exact branch name <branchSpec> existss
                fqbn = "refs/remotes/" + repository + "/" + branchSpec;
                verbose(listener, "Qualifying {0} as a branch in repository {1} -> {2}", branchSpec, repository, fqbn);
                possibleQualifiedBranches.add(fqbn);
            }
            for (String fqbn : possibleQualifiedBranches) {
              revisions.addAll(getHeadRevision(isPollCall, fqbn, git, listener, data));
            }
        }

        if (revisions.isEmpty()) {
            // the 'branch' could actually be a non branch reference (for example a tag or a gerrit change)

            revisions = getHeadRevision(isPollCall, branchSpec, git, listener, data);
            if (!revisions.isEmpty()) {
                verbose(listener, "{0} seems to be a non-branch reference (tag?)");
            }
        }
        
        return revisions;
    }

    private Collection<Revision> getHeadRevision(boolean isPollCall, String singleBranch, GitClient git, TaskListener listener, BuildData data) throws InterruptedException {
        try {
            ObjectId sha1 = git.revParse(singleBranch);
            verbose(listener, "rev-parse {0} -> {1}", singleBranch, sha1);

            // if polling for changes don't select something that has
            // already been built as a build candidate
            if (isPollCall && hasBeenBuilt(data, sha1, singleBranch)) {
                verbose(listener, "{0} has already been built", sha1);
                return emptyList();
            }

            verbose(listener, "Found a new commit {0} to be built on {1}", sha1, singleBranch);
            Revision revision = new Revision(sha1);
            revision.getBranches().add(new Branch(singleBranch, sha1));
            return Collections.singletonList(revision);
            /*
            // calculate the revisions that are new compared to the last build
            List<Revision> candidateRevs = new ArrayList<Revision>();
            List<ObjectId> allRevs = git.revListAll(); // index 0 contains the newest revision
            if (data != null && allRevs != null) {
                Revision lastBuiltRev = data.getLastBuiltRevision();
                if (lastBuiltRev == null) {
                    return Collections.singletonList(objectId2Revision(singleBranch, sha1));
                }
                int indexOfLastBuildRev = allRevs.indexOf(lastBuiltRev.getSha1());
                if (indexOfLastBuildRev == -1) {
                    // mhmmm ... can happen when branches are switched.
                    return Collections.singletonList(objectId2Revision(singleBranch, sha1));
                }
                List<ObjectId> newRevisionsSinceLastBuild = allRevs.subList(0, indexOfLastBuildRev);
                // translate list of ObjectIds into list of Revisions
                for (ObjectId objectId : newRevisionsSinceLastBuild) {
                    candidateRevs.add(objectId2Revision(singleBranch, objectId));
                }
            }
            if (candidateRevs.isEmpty()) {
                return Collections.singletonList(objectId2Revision(singleBranch, sha1));
            }
            return candidateRevs;
            */
        } catch (GitException e) {
            // branch does not exist, there is nothing to build
            verbose(listener, "Failed to rev-parse: {0}", singleBranch);
            return emptyList();
        }
    }

    private Revision objectId2Revision(String singleBranch, ObjectId sha1) {
        Revision revision = new Revision(sha1);
        revision.getBranches().add(new Branch(singleBranch, sha1));
        return revision;
    }

    private boolean hasBeenBuilt(BuildData data, ObjectId sha1, String branch) {
    	try {
            for(Map.Entry<String, Build> buildByBranchName : data.buildsByBranchName.entrySet()) {
                String branchName = buildByBranchName.getKey();
                if (branchName == null) {
                    branchName = "";
                }
                Build build = buildByBranchName.getValue();

                if(branchName.equals(branch) && (build.revision.getSha1().equals(sha1) || build.marked.getSha1().equals(sha1)))
                    return true;
            }

            return false;
    	}
    	catch(Exception ex) {
            return false;
    	}
    }

    /**
     * In order to determine which Revisions to build.
     *
     * Does the following :
     *  1. Find all the branch revisions
     *  2. Filter out branches that we don't care about from the revisions.
     *     Any Revisions with no interesting branches are dropped.
     *  3. Get rid of any revisions that are wholly subsumed by another
     *     revision we're considering.
     *  4. Get rid of any revisions that we've already built.
     *  5. Sort revisions from old to new.
     *
     *  NB: Alternate BuildChooser implementations are possible - this
     *  may be beneficial if "only 1" branch is to be built, as much of
     *  this work is irrelevant in that usecase.
     * @throws IOException
     * @throws GitException
     */
    private List<Revision> getAdvancedCandidateRevisions(boolean isPollCall, TaskListener listener, GitClient git, BuildData data, BuildChooserContext context) throws GitException, IOException, InterruptedException {
        GitUtils utils = new GitUtils(listener, git);

        EnvVars env = context.getEnvironment();

        // 1. Get all the (branch) revisions that exist
        List<Revision> revs = new ArrayList<Revision>(utils.getAllBranchRevisions());
        verbose(listener, "Starting with all the branches: {0}", revs);

        // 2. Filter out any revisions that don't contain any branches that we
        // actually care about (spec)
        for (Iterator<Revision> i = revs.iterator(); i.hasNext();) {
            Revision r = i.next();

            // filter out uninteresting branches
            for (Iterator<Branch> j = r.getBranches().iterator(); j.hasNext();) {
                Branch b = j.next();
                boolean keep = false;
                for (BranchSpec bspec : gitSCM.getBranches()) {
                    if (bspec.matches(b.getName(), env)) {
                        keep = true;
                        break;
                    }
                }

                if (!keep) {
                    verbose(listener, "Ignoring {0} because it doesn''t match branch specifier", b);
                    j.remove();
                }
            }
            
            // filter out HEAD ref if it's not the only ref
            if (r.getBranches().size() > 1) {
                for (Iterator<Branch> j = r.getBranches().iterator(); j.hasNext();) {
                    Branch b = j.next();
                    if (HEAD.matches(b.getName(), env)) {
                    	verbose(listener, "Ignoring {0} because there''s named branch for this revision", b.getName());
                    	j.remove();
                    }
                }
            }

            if (r.getBranches().size() == 0) {
                verbose(listener, "Ignoring {0} because we don''t care about any of the branches that point to it", r);
                i.remove();
            }
        }

        verbose(listener, "After branch filtering: {0}", revs);

        // 3. We only want 'tip' revisions
        revs = utils.filterTipBranches(revs);
        verbose(listener, "After non-tip filtering: {0}", revs);

        // 4. Finally, remove any revisions that have already been built.
        verbose(listener, "Removing what''s already been built: {0}", data.getBuildsByBranchName());
        Revision lastBuiltRevision = data.getLastBuiltRevision();
        for (Iterator<Revision> i = revs.iterator(); i.hasNext();) {
            Revision r = i.next();
            listener.getLogger().println("checking " + r.getSha1String());

            // remove branches which have already been built with this commit
            for (Iterator<Branch> j = r.getBranches().iterator(); j.hasNext();) {
                Branch b = j.next();
                listener.getLogger().println("checking branch " + b.getName());
                if (hasBeenBuilt(data, r.getSha1(), b.getName())) {
                    listener.getLogger().println("branch+commit already built: " + b.getName() + " " + r.getSha1String());
                    j.remove();
                }
            }
            if (r.getBranches().size() == 0) {
                i.remove();
                
                // keep track of new branches pointing to the last built revision
                if (lastBuiltRevision != null && lastBuiltRevision.getSha1().equals(r.getSha1())) {
                	lastBuiltRevision = r;
                }
            }
        }
        verbose(listener, "After filtering out what''s already been built: {0}", revs);

        // Take each branch a revision hasn't been built with, and create a new revision object for that branch.
        // The new `revs` list is a list of revisions with one branch per revision (with some revisions duplicated).
        // We do this for 2 reasons.
        // 1. If there are multiple pending revisions to build, the git plugin will kick off a build job for each one
        // 2. The plugin stores the list of branches built for each revision in the history data.
        List<Revision> revsBranchDups = new ArrayList<Revision>();
        for (Iterator<Revision> i = revs.iterator(); i.hasNext();) {
            Revision r = i.next();
            for (Iterator<Branch> j = r.getBranches().iterator(); j.hasNext();) {
                Branch b = j.next();
                Revision rb = r.clone();
                rb.getBranches().clear();
                rb.getBranches().add(b);
                revsBranchDups.add(rb);
            }
        }
        revs = revsBranchDups;
        verbose(listener, "After exploding branches: {0}", revs);

        // if we're trying to run a build (not an SCM poll) and nothing new
        // was found then just run the last build again but ensure that the branch list
        // is ordered according to the configuration. Sorting the branch list ensures
        // a deterministic value for GIT_BRANCH and allows a git-flow style workflow
        // with fast-forward merges between branches
        if (!isPollCall && revs.isEmpty() && lastBuiltRevision != null) {
            verbose(listener, "Nothing seems worth building, so falling back to the previously built revision: {0}", data.getLastBuiltRevision());
            return Collections.singletonList(utils.sortBranchesForRevision(lastBuiltRevision, gitSCM.getBranches(), env));
        }

        // 5. sort them by the date of commit, old to new
        // this ensures the fairness in scheduling.
        final List<Revision> in = revs;
        return git.withRepository(new RepositoryCallback<List<Revision>>() {
            public List<Revision> invoke(Repository repo, VirtualChannel channel) throws IOException, InterruptedException {
                Collections.sort(in,new CommitTimeComparator(repo));
                return in;
            }
        });
    }

    /**
     * Write the message to the listener only when the verbose mode is on.
     */
    private void verbose(TaskListener listener, String format, Object... args) {
        if (GitSCM.VERBOSE)
            listener.getLogger().println(MessageFormat.format(format,args));
    }

    @Extension
    public static final class DescriptorImpl extends BuildChooserDescriptor {
        @Override
        public String getDisplayName() {
            return "Build branches";
        }

        @Override
        public String getLegacyId() {
            return "BuildBranches";
        }
    }
}