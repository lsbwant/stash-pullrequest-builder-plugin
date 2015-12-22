package stashpullrequestbuilder.stashpullrequestbuilder;

import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashApiClient;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestComment;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestMergableResponse;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestResponseValue;
import stashpullrequestbuilder.stashpullrequestbuilder.stash.StashPullRequestResponseValueRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jfree.util.Log;

/**
 * Created by Nathan McCarthy
 */
public class StashRepository {
    private static final Logger logger = Logger.getLogger(StashRepository.class.getName());
    public static final String BUILD_START_MARKER = "[*BuildStarted* **%s**] %s into %s";
    public static final String BUILD_FINISH_MARKER = "[*BuildFinished* **%s**] %s into %s";

    public static final String BUILD_START_REGEX = "\\[\\*BuildStarted\\* \\*\\*%s\\*\\*\\] ([0-9a-fA-F]+) into ([0-9a-fA-F]+)";
    public static final String BUILD_FINISH_REGEX = "\\[\\*BuildFinished\\* \\*\\*%s\\*\\*\\] ([0-9a-fA-F]+) into ([0-9a-fA-F]+)";

    public static final String BUILD_FINISH_SENTENCE = BUILD_FINISH_MARKER + " \n\n **[%s](%s)** - Build #%d";
    public static final String BUILD_START_SENTENCE = BUILD_START_MARKER + " \n\n **[%s](%s)** - Build #%d";

    public static final String BUILD_SUCCESS_COMMENT =  "✓ BUILD SUCCESS";
    public static final String BUILD_FAILURE_COMMENT = "✕ BUILD FAILURE";
    public static final String BUILD_RUNNING_COMMENT = "BUILD RUNNING...";

    private String projectPath;
    private StashPullRequestsBuilder builder;
    private StashBuildTrigger trigger;
    private StashApiClient client;

    public StashRepository(String projectPath, StashPullRequestsBuilder builder) {
        this.projectPath = projectPath;
        this.builder = builder;
    }

    public void init() {
        trigger = this.builder.getTrigger();
        client = new StashApiClient(
                trigger.getStashHost(),
                trigger.getUsername(),
                trigger.getPassword(),
                trigger.getProjectCode(),
                trigger.getRepositoryName());
    }

    public Collection<StashPullRequestResponseValue> getTargetPullRequests() {
    	myLogger("Fetch PullRequests.");
        List<StashPullRequestResponseValue> pullRequests = client.getPullRequests();
        List<StashPullRequestResponseValue> targetPullRequests = new ArrayList<StashPullRequestResponseValue>();
        for(StashPullRequestResponseValue pullRequest : pullRequests) {
        	myLogger("P&R:" + pullRequest.getTitle());
            if (isBuildTarget_(pullRequest)) {
            	myLogger("add pullRequest " + pullRequest.getTitle() + " to targetPullRequests");
                targetPullRequests.add(pullRequest);
            }
        }
        return targetPullRequests;
    }

    public String postBuildStartCommentTo(StashPullRequestResponseValue pullRequest) {
            String sourceCommit = pullRequest.getFromRef().getCommit().getHash();
            String destinationCommit = pullRequest.getToRef().getCommit().getHash();
            String comment = String.format(BUILD_START_MARKER, builder.getProject().getDisplayName(), sourceCommit, destinationCommit);
            StashPullRequestComment commentResponse = this.client.postPullRequestComment(pullRequest.getId(), comment);
            return commentResponse.getCommentId().toString();
    }

    public void addFutureBuildTasks(Collection<StashPullRequestResponseValue> pullRequests) {
        for(StashPullRequestResponseValue pullRequest : pullRequests) {
            String commentId = postBuildStartCommentTo(pullRequest);
            StashCause cause = new StashCause(
                    trigger.getStashHost(),
                    pullRequest.getFromRef().getBranch().getName(),
                    pullRequest.getToRef().getBranch().getName(),
                    pullRequest.getFromRef().getRepository().getProjectName(),
                    pullRequest.getFromRef().getRepository().getRepositoryName(),
                    pullRequest.getId(),
                    pullRequest.getToRef().getRepository().getProjectName(),
                    pullRequest.getToRef().getRepository().getRepositoryName(),
                    pullRequest.getTitle(),
                    pullRequest.getFromRef().getCommit().getHash(),
                    pullRequest.getToRef().getCommit().getHash(),
                    commentId);
            myLogger("P&R:" + pullRequest.getTitle() + " start job");
            this.builder.getTrigger().startJob(cause);
            
        }
    }

    public void deletePullRequestComment(String pullRequestId, String commentId) {
        this.client.deletePullRequestComment(pullRequestId, commentId);
    }

    public void postFinishedComment(String pullRequestId, String sourceCommit,  String destinationCommit, boolean success, String buildUrl, int buildNumber, String additionalComment) {
        String message = BUILD_FAILURE_COMMENT;
        if (success){
            message = BUILD_SUCCESS_COMMENT;
        }
        String comment = String.format(BUILD_FINISH_SENTENCE, builder.getProject().getDisplayName(), sourceCommit, destinationCommit, message, buildUrl, buildNumber);

        comment = comment.concat(additionalComment);

        this.client.postPullRequestComment(pullRequestId, comment);
    }

    private Boolean isPullRequestMergable(StashPullRequestResponseValue pullRequest) {
        if (trigger.isCheckMergeable() || trigger.isCheckNotConflicted()) {
            StashPullRequestMergableResponse mergable = client.getPullRequestMergeStatus(pullRequest.getId());
            if (trigger.isCheckMergeable())
                return  mergable.getCanMerge();
            if (trigger.isCheckNotConflicted())
                return !mergable.getConflicted();
        }
        return true;
    }

    private boolean isBuildTarget_(StashPullRequestResponseValue pullRequest){
    	boolean isTarget = this.isBuildTarget(pullRequest);
    	myLogger("pullRequest: " + pullRequest.getTitle() +  "isBuildTarget: " + isTarget);
    	return isTarget;
    }
    
    private void myLogger(String msg){
    	logger.info("Thread-" + Thread.currentThread().getId() + " JobName=" + trigger.getjobName() + "---" + msg);
    }
    
    private boolean isBuildTarget(StashPullRequestResponseValue pullRequest) {

        boolean shouldBuild = true;

        if (pullRequest.getState() != null && pullRequest.getState().equals("OPEN")) {
            if (isSkipBuild(pullRequest.getTitle())) {
                return false;
            }

            if(!isPullRequestMergable(pullRequest)) {
                return false;
            }
            
            if (!isConfigedToBranch(pullRequest)) {
                return false;
            }
            
            if (trigger.isOnlyBuildOnComment()) {
                shouldBuild = false;
            }
            

            String sourceCommit = pullRequest.getFromRef().getCommit().getHash();

            StashPullRequestResponseValueRepository destination = pullRequest.getToRef();
            String owner = destination.getRepository().getProjectName();
            String repositoryName = destination.getRepository().getRepositoryName();
            String destinationCommit = destination.getCommit().getHash();

            String id = pullRequest.getId();
            List<StashPullRequestComment> comments = client.getPullRequestComments(owner, repositoryName, id);

            if (comments != null) {
                Collections.sort(comments);
                Collections.reverse(comments);
                for (StashPullRequestComment comment : comments) {
                    String content = comment.getText();
                    if (content == null || content.isEmpty()) {
                        continue;
                    }

                    //These will match any start or finish message -- need to check commits
                    String project_build_start = String.format(BUILD_START_REGEX, builder.getProject().getDisplayName());
                    String project_build_finished = String.format(BUILD_FINISH_REGEX, builder.getProject().getDisplayName());
                    Matcher startMatcher = Pattern.compile(project_build_start, Pattern.CASE_INSENSITIVE).matcher(content);
                    Matcher finishMatcher = Pattern.compile(project_build_finished, Pattern.CASE_INSENSITIVE).matcher(content);

                    if (startMatcher.find() ||
                        finishMatcher.find()) {

                        String sourceCommitMatch;
                        String destinationCommitMatch;

                        if (startMatcher.find(0)) {
                            sourceCommitMatch = startMatcher.group(1);
                            destinationCommitMatch = startMatcher.group(2);
                        } else {
                            sourceCommitMatch = finishMatcher.group(1);
                            destinationCommitMatch = finishMatcher.group(2);
                        }

                        //first check source commit -- if it doesn't match, just move on. If it does, investigate further.
                        if (sourceCommitMatch.equalsIgnoreCase(sourceCommit)) {
                            // if we're checking destination commits, and if this doesn't match, then move on.
                            if (this.trigger.getCheckDestinationCommit()
                                    && (!destinationCommitMatch.equalsIgnoreCase(destinationCommit))) {
                            	continue;
                            }

                            shouldBuild = false;
                            break;
                        }
                    }

                    if (isPhrasesContain(content, this.trigger.getCiBuildPhrases())) {
                        shouldBuild = true;
                        break;
                    }
                }
            }
        }
        
        return shouldBuild;
    }

    private boolean isSkipBuild(String pullRequestTitle) {
        String skipPhrases = this.trigger.getCiSkipPhrases();
        if (skipPhrases != null && !"".equals(skipPhrases)) {
            String[] phrases = skipPhrases.split(",");
            for(String phrase : phrases) {
                if (isPhrasesContain(pullRequestTitle, phrase)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isConfigedToBranch(StashPullRequestResponseValue pullRequest){
    	String targetBranch = pullRequest.getToRef().getBranch().getName();
    	String configuredToBranch = this.trigger.getConfiguredToBranch();
    	if(configuredToBranch == null || configuredToBranch.equals("")){
    		myLogger("P&R: " + pullRequest.getTitle() + "--- configure to branch set with no values, so all branch are potential valid target branch");
    		return true;
    	}else{
	    	if(configuredToBranch.equals(targetBranch)){
	    		myLogger("P&R: " + pullRequest.getTitle() + "--- target Branch " + targetBranch + "  matches to configured to branch " + configuredToBranch + ", trigger the CI process");
	    		return true;
	    	}else{
	    		myLogger("P&R: " + pullRequest.getTitle() + "--- target Branch " + targetBranch + " do not matches to configured to branch " + configuredToBranch + " , do not trigger the CI process");
	    		return false;
	    	}
    	}
    	
    }
    
    private boolean isPhrasesContain(String text, String phrase) {
        return text != null && text.toLowerCase().contains(phrase.trim().toLowerCase());
    }
}
