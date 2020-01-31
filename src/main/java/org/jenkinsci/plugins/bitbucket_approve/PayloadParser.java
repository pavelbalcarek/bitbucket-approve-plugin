package org.jenkinsci.plugins.bitbucket_approve;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.jenkinsci.plugins.bitbucket_approve.model.BitbucketPayloadModelBase;
import org.jenkinsci.plugins.bitbucket_approve.model.BitbucketPullRequestPayloadModel;
import org.jenkinsci.plugins.bitbucket_approve.model.BitbucketPushPayloadModel;

import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

public final class PayloadParser {

    private static transient final Logger LOG = Logger.getLogger(PayloadParser.class.getName());

    public static BitbucketPayloadModelBase parse(AbstractBuild<?, ?> build, BuildListener listener,
            String payloadEnvVariableOrContent) throws Exception {

        String payloadContent = extractEnvironmentVariable(build, listener, payloadEnvVariableOrContent);
        LOG.debug("Bitbucket Approve: Bitbucket payload (parse) = " + payloadContent);
       
        JSONObject payloadObject = JSONObject.fromObject(payloadContent);

        String eventKey = payloadObject.getString("eventKey");
        if (eventKey.startsWith("repo:")) {
            return parsePushPayload(build, listener, payloadObject);
        } else if (eventKey.startsWith("pr:")) {
            return parsePullRequestPayload(build, listener, payloadObject);
        }

        throw new Exception("Unable to find valid payloads");
    }

    private static BitbucketPullRequestPayloadModel parsePullRequestPayload(AbstractBuild<?, ?> build,
            BuildListener listener, JSONObject payloadObject) {

        String projectKey = "";
        String repositorySlug = "";
        String pullRequestId = "";
        String sourceCommitHash = "";

        try {
            JSONObject pullRequestObject = payloadObject.getJSONObject("pullRequest");
            JSONObject fromRefObject = pullRequestObject.getJSONObject("fromRef");
            JSONObject repositoryObject = fromRefObject.getJSONObject("repository");

            projectKey = repositoryObject.getJSONObject("project").getString("key");
            repositorySlug = repositoryObject.getString("slug");
            pullRequestId = pullRequestObject.getString("id");

            sourceCommitHash = fromRefObject.getString("latestCommit");

        } catch (JSONException err) {
            LOG.error("Can't parse bitbucket payload");
        }

        return new BitbucketPullRequestPayloadModel(sourceCommitHash, projectKey, repositorySlug, pullRequestId);
    }

    private static BitbucketPushPayloadModel parsePushPayload(AbstractBuild<?, ?> build, BuildListener listener,
        JSONObject payloadObject) {

        String sourceCommitHash = "";

        try {
            JSONArray changeObjects = payloadObject.getJSONArray("changes");
            sourceCommitHash = changeObjects.getJSONObject(0).getString("toHash");
        } catch (JSONException err) {
            LOG.error("Can't parse bitbucket payload");
        }

        return new BitbucketPushPayloadModel(sourceCommitHash);
    }

    private static String extractEnvironmentVariable(AbstractBuild<?, ?> build, BuildListener listener,
            String variableName) {
        String extractedVariable = "";
        try {
            extractedVariable = build.getEnvironment(listener).expand(variableName);
        } catch (IOException e) {
        } catch (InterruptedException e) {
        }

        return extractedVariable;
    }
}