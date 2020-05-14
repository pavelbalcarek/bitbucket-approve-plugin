package org.jenkinsci.plugins.bitbucket_approve;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.AbstractIdCredentialsListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import okhttp3.Credentials;
import okhttp3.Request;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.bitbucket_approve.model.BitbucketPayloadModelBase;
import org.jenkinsci.plugins.bitbucket_approve.model.BitbucketPullRequestPayloadModel;
import org.jenkinsci.plugins.bitbucket_approve.model.BitbucketPushPayloadModel;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.CheckForNull;
import javax.inject.Singleton;

@SuppressWarnings("unused") // This class will be loaded using its Descriptor.
public class BitbucketApprover extends Notifier implements SimpleBuildStep {

    private static transient final LoggerInternal LOG = LoggerInternal.getLogger();

    private static transient Class<StandardUsernamePasswordCredentials> ACCEPTED_CREDENTIALS = StandardUsernamePasswordCredentials.class;

    private boolean mApproveUnstable;

    private String mApprovalMethod;

    private String mBitbucketPayload;

    private RequestBuilder mRequestBuilder;

    @DataBoundConstructor
    public BitbucketApprover(boolean approveUnstable, String approvalMethod, String bitbucketPayload) {
        mApproveUnstable = approveUnstable;
        mApprovalMethod = approvalMethod;
        mBitbucketPayload = bitbucketPayload;
    }

    public boolean getApproveUnstable() {
        return mApproveUnstable;
    }

    public String getApprovalMethod() {
        return mApprovalMethod;
    }

    public String getBitbucketPayload() {
        return mBitbucketPayload;
    }

    private RequestBuilder getRequestBuilder() {
        if (mRequestBuilder == null) {
            mRequestBuilder = new RequestBuilder(getDescriptor());
        }

        return mRequestBuilder;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        LOG.debug("perform started");

        BitbucketPayloadModelBase payload = null;
        try {
            payload = PayloadParser.parse(build, listener, mBitbucketPayload);
        } catch (Exception e) {
            LOG.error("unable to parse payload.", e);
        }

        // Previous versions did not know about approval method. This makes sure the
        // upgrade
        // does not break builds and we keep the same behaviour as before.
        if (mApprovalMethod == null) {
            mApprovalMethod = "approveOnly";
        }

        LOG.doLogAndPrint("Using bitbucket endpoint: " + getDescriptor().getBitbucketUrl(), listener);

        if (!mApprovalMethod.equals("statusOnly")) {
            if (payload instanceof BitbucketPullRequestPayloadModel) {
                approveBuild(build, listener, (BitbucketPullRequestPayloadModel) payload);
            } else {
                LOG.doLogAndPrint("Unable to approve build - pull request payload was expected, but given: "
                        + payload.getClass().getTypeName() + ".", listener);
                throw new IOException("Bitbucket pull request payload expected");
            }
        } else {
            LOG.doLogAndPrint("Skipping approval because we only set the status.", listener);
        }

        if (!mApprovalMethod.equals("approveOnly")) {
            postBuildStatus(build, listener, payload);
        } else {
            LOG.doLogAndPrint("Skipping build status because we only approve commits.", listener);
        }

        LOG.debug("perform finished");
    }

    private void approveBuild(Run<?, ?> build, TaskListener listener, BitbucketPullRequestPayloadModel payload)
            throws IOException {
        String url = String.format("rest/api/1.0/projects/%s/repos/%s/pull-requests/%s/participants/%s",
                payload.getProjectKey(), payload.getRepositorySlug(), payload.getPullRequestId(),
                getDescriptor().getUsername());

        String approveStatus = "UNAPPROVED";
        if (this.isBuildSuccessfulOrRunning(build)) {
            approveStatus = "APPROVED";
        } else {
            approveStatus = "NEEDS_WORK";
        }

        String json = "{ " + "\"user\": { \"name\": \"" + getDescriptor().getUsername() + "\" " + "}, "
                + "\"approved\": true, " + "\"status\": \"" + approveStatus + "\" " + "}";

        Request request = getRequestBuilder().buildRequest(url, "PUT", json);

        LOG.doLogAndPrint(String.format("Approve build: url = %s, payload = %s, credentials id = %s",
                request.url().toString(), json, getDescriptor().getCredentialId()), listener);

        RequestSender.sendRequest(request, listener, getDescriptor().getHttpClientIgnoreSSL());
    }

    private void postBuildStatus(Run<?, ?> build, TaskListener listener, BitbucketPayloadModelBase payload)
            throws IOException {

        String commitHash = payload.getSourceCommitHash();
        String url = String.format("rest/build-status/1.0/commits/%s", commitHash);

        String state = this.isBuildSuccessfulOrRunning(build) ? "SUCCESSFUL" : "FAILED";
        String key = this.getJobKey(build);
        String rootUrl = Jenkins.getInstance().getRootUrl();
        String buildUrl = (rootUrl == null) ? "PLEASE SET JENKINS ROOT URL IN GLOBAL CONFIG " + build.getUrl()
                : rootUrl + build.getUrl();
        String description = build.getDisplayName() + ' ' + commitHash.substring(0, 7);

        String json = "{\"state\": \"" + state + "\", \"key\": \"" + key + "\", \"name\": \"" + key + "\", \"url\": \""
                + buildUrl + "\", \"description\": \"" + description + "\"}";

        Request request = getRequestBuilder().buildRequest(url, "POST", json);

        LOG.doLogAndPrint(String.format("Build status: url = %s, payload = %s, credentials id = %s",
                request.url().toString(), json, getDescriptor().getCredentialId()), listener);

        RequestSender.sendRequest(request, listener, getDescriptor().getHttpClientIgnoreSSL());
    }

    private String getJobKey(Run<?, ?> build) {
        if (build.getParent() != null && StringUtils.isNotBlank(build.getParent().getName())) {
            return build.getParent().getName();
        }

        return build.getDisplayName();
    }

    private boolean isBuildSuccessfulOrRunning(Run<?, ?> build) {
        Result result = build.getResult();
        if (result == null) {
            // we are probably in pipeline "normal steps", where status can be null
            // but because pipeline is still running, then probably is ok
            return true;
        }
        if (result.equals(Result.SUCCESS) || (result.equals(Result.UNSTABLE) && mApproveUnstable)) {
            return true;
        }

        return false;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private transient String mUser;

        private transient String mPassword;

        private String mCredentialId;

        private String mBitbucketUrl;

        private Boolean mHttpClientIgnoreSSL;

        private transient Boolean mRefreshConfiguration = false;

        /**
         * In order to load the persisted global configuration, you have to call load()
         * in the constructor.
         */
        public DescriptorImpl() {
            load();

            configureCredentials(mCredentialId);
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Approve commit on Bitbucket";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            LOG.debug("Configure (data): " + formData.toString());

            configureCredentials(formData);
            configureEndpoint(formData);

            Boolean currentHttpClientIgnoreSSL = formData.getBoolean("httpClientIgnoreSSL");
            mRefreshConfiguration = currentHttpClientIgnoreSSL != mHttpClientIgnoreSSL;
            mHttpClientIgnoreSSL = currentHttpClientIgnoreSSL;

            save();

            return super.configure(req, formData);
        }

        public String getUsername() {
            return mUser;
        }

        public String getBasicAuth() {
            return Credentials.basic(mUser, mPassword);
        }

        public String getCredentialId() {
            return mCredentialId;
        }

        public String getBitbucketUrl() {
            return mBitbucketUrl;
        }

        public Boolean getHttpClientIgnoreSSL() {
            return mHttpClientIgnoreSSL;
        }

        public Boolean getRefreshConfiguration() {
            return mRefreshConfiguration;
        }

        public FormValidation doSendTestApproval(@AncestorInPath AbstractProject<?, ?> context,
                @QueryParameter String bitbucketUrl, @QueryParameter Boolean httpClientIgnoreSSL,
                @QueryParameter String credentialId) {
            StandardUsernamePasswordCredentials credentials = CredentialUtils.resolveCredential(credentialId);

            if (credentials == null) {
                return FormValidation.error("Failed to get credentials");
            }

            String basicAuth = Credentials.basic(credentials.getUsername(), credentials.getPassword().getPlainText());
            Request request = RequestBuilder.buildRequest(bitbucketUrl, basicAuth,
                    "rest/api/1.0/users?filter=" + credentials.getUsername(), "GET", null);

            try {
                RequestSender.sendRequest(request, null, httpClientIgnoreSSL);
                return FormValidation.ok("Connected with " + credentials.getUsername() + " to " + bitbucketUrl);
            } catch (Exception e) {
                return FormValidation.error(e, "Failed to connect");
            }
        }

        public ListBoxModel doFillCredentialIdItems(@AncestorInPath Item context) {
            return CredentialUtils.getAvailableCredentials(context, mCredentialId);
        }

        private void configureCredentials(JSONObject formData) {
            mCredentialId = formData.getString("credentialId");

            configureCredentials(mCredentialId);
        }

        private void configureCredentials(String credentialId) {
            StandardUsernamePasswordCredentials credentials = CredentialUtils.resolveCredential(credentialId);
            if (credentials == null) {
                LOG.debug("Credentials not configured, skipping configuration");
                return;

            }
            mUser = credentials.getUsername();
            mPassword = Secret.toString(credentials.getPassword());

            LOG.debug("Configure, credentials: " + mUser + " [" + mCredentialId + "]");
        }

        private void configureEndpoint(JSONObject formData) {
            mBitbucketUrl = formData.getString("bitbucketUrl");

            if (StringUtils.isEmpty(mBitbucketUrl)) {
                mBitbucketUrl = "https://api.bitbucket.org/2.0";
                LOG.warn("Bitbucket url is not set, using default: " + mBitbucketUrl);
            }

            if (mBitbucketUrl.startsWith("http://") == false && mBitbucketUrl.startsWith("https://") == false) {
                mBitbucketUrl = "https://" + mBitbucketUrl;
            }

            LOG.debug("Configure, bitbucket url: " + mBitbucketUrl);
        }

        @Singleton
        public static class CredentialUtils {

            public static StandardUsernamePasswordCredentials resolveCredential(@CheckForNull String credentialId) {
                return CredentialUtils.resolveCredential(null, credentialId);
            }

            public static StandardUsernamePasswordCredentials resolveCredential(AbstractProject<?, ?> context,
                    @CheckForNull String credentialId) {

                return credentialId == null ? null
                        : CredentialsMatchers.firstOrNull(CredentialsProvider.lookupCredentials(ACCEPTED_CREDENTIALS,
                                context, ACL.SYSTEM, getDomainRequirements()),
                                CredentialsMatchers.withId(credentialId));
            }

            public static ListBoxModel getAvailableCredentials(@CheckForNull Item item, String globalCredentialId) {
                String currentValue = getCurrentlySelectedCredentialId(item, globalCredentialId);
                if ((item == null && !Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER))
                        || item != null && !item.hasPermission(Item.EXTENDED_READ)) {
                    return new StandardListBoxModel().includeCurrentValue(currentValue);
                }
                AbstractIdCredentialsListBoxModel<StandardListBoxModel, StandardCredentials> model = new StandardListBoxModel()
                        .includeEmptyValue();
                if (item == null) {
                    model = model.includeAs(ACL.SYSTEM, Jenkins.getInstance(), ACCEPTED_CREDENTIALS,
                            getDomainRequirements());
                } else {
                    model = model.includeAs(ACL.SYSTEM, item, ACCEPTED_CREDENTIALS, getDomainRequirements());
                }
                if (currentValue != null) {
                    model = model.includeCurrentValue(currentValue);
                }
                return model;
            }

            private static String getCurrentlySelectedCredentialId(Item item, String globalCredentialId) {
                if (item == null) {
                    return globalCredentialId;
                } else if (item instanceof AbstractProject) {
                    BitbucketApprover notifier = ((AbstractProject<?, ?>) item).getPublishersList()
                            .get(BitbucketApprover.class);
                    return notifier == null ? null : notifier.getDescriptor().getCredentialId();
                } else {
                    return null;
                }
            }

            private static List<DomainRequirement> getDomainRequirements() {
                return Collections.<DomainRequirement>emptyList();
            }
        }
    }
}