package org.jenkinsci.plugins.bitbucket_approve;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.AbstractIdCredentialsListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
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
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import java.io.IOException;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.CheckForNull;
import javax.inject.Singleton;

@SuppressWarnings("unused") // This class will be loaded using its Descriptor.
public class BitbucketApprover extends Notifier {

    private static transient final Logger LOG = Logger.getLogger(BitbucketApprover.class.getName());

    private static transient Class<StandardUsernamePasswordCredentials> ACCEPTED_CREDENTIALS = StandardUsernamePasswordCredentials.class;

    private transient OkHttpClient mClient;

    private boolean mApproveUnstable;

    private String mApprovalMethod;

    private String mBitbucketPayload;

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

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        LOG.debug("Bitbucket Approve: perform started");

        BuildData buildData = build.getAction(BuildData.class);
        if (buildData == null) {
            doLogAndPrint("Bitbucket Approve: Could not get build data from build.", listener);
            return false;
        }

        Revision rev = buildData.getLastBuiltRevision();
        if (rev == null) {
            doLogAndPrint("Bitbucket Approve: Could not get revision from build.", listener);
            return false;
        }

        String commitHash = rev.getSha1String();
        if (commitHash == null) {
            doLogAndPrint("Bitbucket Approve: Could not get commit hash from build data.", listener);
            return false;
        }
        
        // Previous versions did not know about approval method. This makes sure the upgrade
        // does not break builds and we keep the same behaviour as before.
        if (mApprovalMethod == null) {
            mApprovalMethod = "approveOnly";
        }

        doLogAndPrint("Using bitbucket endpoint: " + getDescriptor().getBitbucketUrl(), listener);
        
        if (!mApprovalMethod.equals("statusOnly")) {
            approveBuild(build, listener, commitHash);
        } else {
            doLogAndPrint("Bitbucket Approve: Skipping approval because we only set the status.", listener);
        }
        
        if (!mApprovalMethod.equals("approveOnly")) {
            postBuildStatus(build, listener, commitHash);
        } else {
            doLogAndPrint("Bitbucket Approve: Skipping build status because we only approve commits.", listener);
        }
        
        LOG.debug("Bitbucket Approve: perform finished");
        return true;
    }

    private void approveBuild(AbstractBuild build, BuildListener listener, String commitHash) throws IOException, InterruptedException {
        String eBitbucketPayload = build.getEnvironment(listener).expand(mBitbucketPayload);

        BitbucketPullRequestPayloadModel pullRequestPayload = PayloadParser.parsePayload(build, listener,
                eBitbucketPayload);

        String url = String.format("%s/rest/api/1.0/projects/%s/repos/%s/pull-requests/%s/participants/%s",
                                    getDescriptor().getBitbucketUrl(),
                                    pullRequestPayload.getProjectKey(),
                                    pullRequestPayload.getRepositorySlug(),
                                    pullRequestPayload.getPullRequestId(),
                                    getDescriptor().getUsername());

        String approveStatus = "UNAPPROVED";
        Result buildResult = build.getResult();
        if (buildResult.equals(Result.SUCCESS) ||
            (buildResult.equals(Result.UNSTABLE) && mApproveUnstable)) {
            approveStatus = "APPROVED";
        } else if (buildResult.isBetterOrEqualTo(Result.UNSTABLE)) {
            approveStatus = "NEEDS_WORK";
        }

        String json = "{ " 
            + "\"user\": { "
            + "  \"name\": \"" + getDescriptor().getUsername() + "\" "
            + "}, "
            + "\"approved\": true, "
            + "\"status\": \"" + approveStatus + "\" "
            + "}";

        MediaType mediaJson = MediaType.parse("application/json; charset=utf-8");
        RequestBody statusBody = RequestBody.create(mediaJson, json);

        doLogAndPrint("Bitbucket Approve: url = " + url, listener);
        doLogAndPrint("Bitbucket Approve: payload = " + json, listener);

        Request.Builder builder = new Request.Builder();
        doLogAndPrint("Bitbucket Approve: Credentials Id: " + getDescriptor().getCredentialId(), listener);
        Request request = builder.header("Authorization", getDescriptor().getBasicAuth())
                .url(url)
                .method("PUT", statusBody).build();

        try {
            Response response = getHttpClient().newCall(request).execute();

            if (!isSuccessful(response)) {
                doLogAndPrint("Bitbucket Approve: " + response.code() + " - " + response.message(), listener);
                doLogAndPrint("Bitbucket Approve: " + response.body().string(), listener);
            }
        } catch (IOException e) {
            e.printStackTrace(listener.getLogger());
        }
    }

    private void postBuildStatus(AbstractBuild build, BuildListener listener, String commitHash) throws IOException, InterruptedException {
        String url = String.format("%s/rest/build-status/1.0/commits/%s", getDescriptor().getBitbucketUrl(),
                                    commitHash);
        doLogAndPrint("Bitbucket Approve: " + url, listener);

        String state = (build.getResult().ordinal == Result.SUCCESS.ordinal) ? "SUCCESSFUL" : "FAILED";
        String key = build.getProject().getDisplayName();
        String name = "Build #" + build.getId();
        String rootUrl = Jenkins.getInstance().getRootUrl();
        String buildUrl = (rootUrl == null) ? "PLEASE SET JENKINS ROOT URL IN GLOBAL CONFIG " + build.getUrl() : rootUrl + build.getUrl();
        String description = build.getProject().getDisplayName() + ' ' + commitHash.substring(0, 7);

        String json = "{\"state\": \"" + state
         + "\",\"key\": \"" + key
         + "\",\"name\": \"" + name
         + "\",\"url\": \"" + buildUrl
         + "\",\"description\": \"" + description + "\"}";

        MediaType mediaJson = MediaType.parse("application/json; charset=utf-8");
        RequestBody statusBody = RequestBody.create(mediaJson, json);

        doLogAndPrint("Bitbucket Approve: Credentials Id:" + getDescriptor().getCredentialId(), listener);
        Request.Builder builder = new Request.Builder();
        Request statusRequest = builder.header("Authorization", getDescriptor().getBasicAuth())
                .url(url)
                .method("POST", statusBody).build();

        try {
            Response statusResponse = getHttpClient().newCall(statusRequest).execute();

            if (!isSuccessful(statusResponse)) {
                doLogAndPrint("Bitbucket Approve (sending status): " + statusResponse.code() + " - " + statusResponse.message(), listener);
                doLogAndPrint("Bitbucket Approve (sending status): " + statusResponse.body().string(), listener);
            }
        } catch (IOException e) {
            e.printStackTrace(listener.getLogger());
            LOG.error(e);
        }
    }

    /**
     * A 409 CONFLICT response means that we already approved this changeset.
     * We do not consider that an error.
     */
    private boolean isSuccessful(Response response) throws IOException {
        return response.isSuccessful() ||
                (response.code() == HttpURLConnection.HTTP_CONFLICT && response.body().string().contains("You already approved this changeset."));
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    private OkHttpClient getHttpClient() {
        if (mClient == null) {
            LOG.debug("Bitbucket Approve: initializing http client");
            mClient = HttpClientUtils.getHttpClient(getDescriptor().getHttpClientIgnoreSSl());
        }

        return mClient;
    }

    private static void doLogAndPrint(String message, BuildListener buildListener) {
        doLogAndPrint(message, buildListener, Level.DEBUG);
    }

    private static void doLogAndPrint(String message, BuildListener buildListener, Level level) {
        if (StringUtils.isEmpty(message)) {
            return;
        }

        if (buildListener != null) {
            PrintStream logger = buildListener.getLogger();
            logger.println(message);
        }

        if (level != null) {
            LOG.log(level, message);
        }
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String mUser;

        private String mPassword;

        private String mCredentialId;

        private String mBitbucketUrl;

        private Boolean mHttpClientIgnoreSSl;

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
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

            mHttpClientIgnoreSSl = formData.getBoolean("httpClientIgnoreSSL");

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

        public Boolean getHttpClientIgnoreSSl() {
            return mHttpClientIgnoreSSl;
        }

        public FormValidation doSendTestApproval(@AncestorInPath AbstractProject<?, ?> context,
                @QueryParameter String bitbucketUrl, @QueryParameter Boolean httpClientIgnoreSSL, @QueryParameter String credentialId) {
            StandardUsernamePasswordCredentials credentials = CredentialUtils.resolveCredential(credentialId);

            if (credentials == null) {
                return FormValidation.error("Failed to get credentials");
            }

            return FormValidation.ok("Credentials found: " + credentials.getUsername()
                                     + " bitbucket url: " + bitbucketUrl);
        }

        public ListBoxModel doFillCredentialIdItems(@AncestorInPath Item context) {
            return CredentialUtils.getAvailableCredentials(context, mCredentialId);
        }

        private void configureCredentials(JSONObject formData) {
            mCredentialId = formData.getString("credentialId");

            StandardUsernamePasswordCredentials credentials = CredentialUtils.resolveCredential(mCredentialId);
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

            if (mBitbucketUrl.startsWith("http://") == false
                && mBitbucketUrl.startsWith("https://") == false) {
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
                        : CredentialsMatchers.firstOrNull(
                            CredentialsProvider.lookupCredentials(ACCEPTED_CREDENTIALS,
                                context,
                                ACL.SYSTEM,
                                getDomainRequirements()),
                            CredentialsMatchers.withId(credentialId));
            }

            public static ListBoxModel getAvailableCredentials(@CheckForNull Item item, String globalCredentialId) {
                String currentValue = getCurrentlySelectedCredentialId(item, globalCredentialId);
                if ((item == null && !Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER))
                        || item != null && !item.hasPermission(Item.EXTENDED_READ)) {
                    return new StandardListBoxModel().includeCurrentValue(currentValue);
                }
                AbstractIdCredentialsListBoxModel<StandardListBoxModel, StandardCredentials> model
                    = new StandardListBoxModel().includeEmptyValue();
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
                    BitbucketApprover notifier = ((AbstractProject<?, ?>) item).getPublishersList().get(BitbucketApprover.class);
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