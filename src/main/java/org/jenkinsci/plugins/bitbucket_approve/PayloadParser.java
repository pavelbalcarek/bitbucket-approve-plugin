package org.jenkinsci.plugins.bitbucket_approve;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.jenkinsci.plugins.bitbucket_approve.BitbucketPullRequestPayloadModel;

import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

public final class PayloadParser {

    private static transient final Logger LOG = Logger.getLogger(PayloadParser.class.getName());

    public static BitbucketPullRequestPayloadModel parsePayload(AbstractBuild<?, ?> build, BuildListener listener,
            String payloadEnvVariableOrContent) {

        String payloadContent = extractEnvironmentVariable(build, listener, payloadEnvVariableOrContent);

        String projectKey = "";
        String repositorySlug = "";
        String pullRequestId = "";

        LOG.debug("Bitbucket Approve: Bitbucket payload = " + payloadContent);
        try {
            JSONObject payloadObject = JSONObject.fromObject(payloadContent);
            JSONObject pullRequestObject = payloadObject.getJSONObject("pullRequest");
            JSONObject repositoryObject = pullRequestObject.getJSONObject("fromRef").getJSONObject("repository");

            projectKey = repositoryObject.getJSONObject("project").getString("key");
            repositorySlug = repositoryObject.getString("slug");
            pullRequestId = pullRequestObject.getString("id");

        } catch (JSONException err) {
            LOG.error("Can't parse bitbucket payload");
        }

        return new BitbucketPullRequestPayloadModel(
            projectKey,
            repositorySlug,
            pullRequestId
        );
    }

    private static String extractEnvironmentVariable(AbstractBuild<?, ?> build, BuildListener listener, String variableName) {
        String extractedVariable = "";
        try {
            extractedVariable = build.getEnvironment(listener).expand(variableName);
        } catch (IOException e) {
        } catch (InterruptedException e) {
        }

        return extractedVariable;
    }
}