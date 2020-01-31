package org.jenkinsci.plugins.bitbucket_approve.model;

public class BitbucketPushPayloadModel extends BitbucketPayloadModelBase {

    public BitbucketPushPayloadModel(String sourceCommitHash) {
        super(sourceCommitHash);
    }
}