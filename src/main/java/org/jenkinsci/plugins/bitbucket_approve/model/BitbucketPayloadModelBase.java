package org.jenkinsci.plugins.bitbucket_approve.model;

public abstract class BitbucketPayloadModelBase {

    private final String mSourceCommitHash;

    public BitbucketPayloadModelBase(String sourceCommitHash) {
        mSourceCommitHash = sourceCommitHash;
    }

    public String getSourceCommitHash() {
        return mSourceCommitHash;
    }
}