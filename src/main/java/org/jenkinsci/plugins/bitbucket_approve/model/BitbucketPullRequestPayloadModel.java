package org.jenkinsci.plugins.bitbucket_approve.model;

public class BitbucketPullRequestPayloadModel extends BitbucketPayloadModelBase {

    private final String mProjectKey;
    private final String mRepositorySlug;
    private final String mPullRequestId;

    public BitbucketPullRequestPayloadModel(String sourceCommitHash, String projectKey, String repositorySlug,
            String pullRequestId) {
        super(sourceCommitHash);

        mProjectKey = projectKey;
        mRepositorySlug = repositorySlug;
        mPullRequestId = pullRequestId;
    }

    public String getPullRequestId() {
        return mPullRequestId;
    }

    public String getRepositorySlug() {
        return mRepositorySlug;
    }

    public String getProjectKey() {
        return mProjectKey;
    }

}