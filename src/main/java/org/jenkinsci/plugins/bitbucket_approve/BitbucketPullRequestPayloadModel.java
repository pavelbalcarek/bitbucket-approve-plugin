package org.jenkinsci.plugins.bitbucket_approve;

public class BitbucketPullRequestPayloadModel {

    private final String mProjectKey;
    private final String mRepositorySlug;
    private final String mPullRequestId;

    public BitbucketPullRequestPayloadModel(String projectKey, String repositorySlug, String pullRequestId) {
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