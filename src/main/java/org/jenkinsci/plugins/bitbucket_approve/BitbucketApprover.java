package org.jenkinsci.plugins.bitbucket_approve;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.AbstractIdCredentialsListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.MediaType;
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
import net.sf.json.JSONObject;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
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

    private final transient OkHttpClient mClient;

    private String mOwner;

    private String mSlug;

    private boolean mApproveUnstable;

    private String mApprovalMethod;

    @DataBoundConstructor
    public BitbucketApprover(String owner, String slug, boolean approveUnstable, String approvalMethod) {
        mOwner = owner;
        mSlug = slug;
        mApproveUnstable = approveUnstable;
        mApprovalMethod = approvalMethod;

        mClient = new OkHttpClient();
        mClient.setConnectTimeout(30, TimeUnit.SECONDS);
        mClient.setReadTimeout(60, TimeUnit.SECONDS);
    }

    public String getSlug() {
        return mSlug;
    }

    public String getOwner() {
        return mOwner;
    }

    public boolean getApproveUnstable() {
        return mApproveUnstable;
    }

    public String getApprovalMethod() {
        return mApprovalMethod;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        LOG.debug("Bitbucket Approve: perform started");
        PrintStream logger = listener.getLogger();
        
        BuildData buildData = build.getAction(BuildData.class);
        if (buildData == null) {
            logger.println("Bitbucket Approve: Could not get build data from build.");
            return false;
        }

        Revision rev = buildData.getLastBuiltRevision();
        if (rev == null) {
            logger.println("Bitbucket Approve: Could not get revision from build.");
            return false;
        }

        String commitHash = rev.getSha1String();
        if (commitHash == null) {
            logger.println("Bitbucket Approve: Could not get commit hash from build data.");
            return false;
        }

        // Previous versions did not know about approval method. This makes sure the upgrade
        // does not break builds and we keep the same behaviour as before.
        if (mApprovalMethod == null) {
            mApprovalMethod = "approveOnly";
        }

        if (!mApprovalMethod.equals("statusOnly")) {
            approveBuild(build, listener, commitHash, logger);
        } else {
            logger.println("Bitbucket Approve: Skipping approval because we only set the status.");
        }

        if (!mApprovalMethod.equals("approveOnly")) {
            postBuildStatus(build, listener, commitHash, logger);
        } else {
            logger.println("Bitbucket Approve: Skipping build status because we only approve commits.");
        }
        
        LOG.debug("Bitbucket Approve: perform finished");
        return true;
    }

    private void approveBuild(AbstractBuild build, BuildListener listener, String commitHash, PrintStream logger) throws IOException, InterruptedException {
        if (build.getResult().isWorseThan(Result.UNSTABLE) ||
                build.getResult().ordinal == Result.UNSTABLE.ordinal && !mApproveUnstable) {
            logger.println("Bitbucket Approve: Skipping approval because build is " + build.getResult().toString());
            return;
        }

        String eOwner = build.getEnvironment(listener).expand(mOwner);
        String eSlug  = build.getEnvironment(listener).expand(mSlug);
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/commit/%s/approve", eOwner, eSlug, commitHash);
        logger.println("Bitbucket Approve: " + url);

        Request.Builder builder = new Request.Builder();
        logger.println("Bitbucket Approve: Credentials Id: " + getDescriptor().getCredentialId());
        Request request = builder.header("Authorization", getDescriptor().getBasicAuth())
                .url(url)
                .method("POST", null).build();

        try {
            Response response = mClient.newCall(request).execute();

            if (!isSuccessful(response)) {
                logger.println("Bitbucket Approve: " + response.code() + " - " + response.message());
                logger.println("Bitbucket Approve: " + response.body().string());
            }
        } catch (IOException e) {
            e.printStackTrace(listener.getLogger());
        }
    }

    private void postBuildStatus(AbstractBuild build, BuildListener listener, String commitHash, PrintStream logger) throws IOException, InterruptedException {
        String eOwner = build.getEnvironment(listener).expand(mOwner);
        String eSlug  = build.getEnvironment(listener).expand(mSlug);
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/commit/%s/statuses/build", eOwner, eSlug, commitHash);
        logger.println("Bitbucket Approve: " + url);

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

        logger.println("Bitbucket Approve: Credentials Id:" + getDescriptor().getCredentialId());
        Request.Builder builder = new Request.Builder();
        Request statusRequest = builder.header("Authorization", getDescriptor().getBasicAuth())
                .url(url)
                .method("POST", statusBody).build();

        try {
            Response statusResponse = mClient.newCall(statusRequest).execute();

            if (!isSuccessful(statusResponse)) {
                logger.println("Bitbucket Approve (sending status): " + statusResponse.code() + " - " + statusResponse.message());
                logger.println("Bitbucket Approve (sending status): " + statusResponse.body().string());
            }
        } catch (IOException e) {
            e.printStackTrace(logger);
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

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String mUser;

        private String mPassword;

        private String mCredentialId;

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
            mCredentialId = formData.getString("credentialId");

            StandardUsernamePasswordCredentials credentials = CredentialUtils.resolveCredential(mCredentialId);
            mUser = credentials.getUsername();
            mPassword = Secret.toString(credentials.getPassword());

            save();

            return super.configure(req, formData);
        }

        public String getBasicAuth() {
            return Credentials.basic(mUser, mPassword);
        }

        public String getCredentialId() {
            return mCredentialId;
        }

        public FormValidation doSendTestApproval(@AncestorInPath AbstractProject<?, ?> context,
                @QueryParameter String credentialId) {
            StandardUsernamePasswordCredentials credentials = CredentialUtils.resolveCredential(credentialId);

            if (credentials == null) {
                return FormValidation.error("Failed to get credentials");
            }

            return FormValidation.ok("Credentials found: " + credentials.getUsername());
        }

        public ListBoxModel doFillCredentialIdItems(@AncestorInPath Item context) {
            return CredentialUtils.getAvailableCredentials(context, mCredentialId);
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