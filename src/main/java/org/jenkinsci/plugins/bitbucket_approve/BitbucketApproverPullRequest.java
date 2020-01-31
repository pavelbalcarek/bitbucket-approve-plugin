// import java.io.IOException;
// import java.io.PrintStream;

// import org.jenkinsci.plugins.bitbucket_approve.BitbucketApprover.DescriptorImpl;

// import hudson.model.AbstractBuild;
// import hudson.model.BuildListener;
// import hudson.model.Result;
// import okhttp3.Request;
// import okhttp3.RequestBody;
// import okhttp3.Response;

// class BitbucketApproverPullRequest {

//     private transient final BitbucketApprover mApprover;

//     private static transient final DescriptorImpl mDescriptor;

//     private transient final Boolean mApproveUnstable;

//     public BitbucketApproverPullRequest(BitbucketApprover approver, Boolean approveUnstable) {
//         mApprover = approver;
//         mDescriptor = approver.getDescriptor();
//         mApproveUnstable = approveUnstable;
//     }

//     private void approveBuild(AbstractBuild build, BuildListener listener, String commitHash, PrintStream logger, String mBitbucketPayload)
//             throws IOException, InterruptedException {
//         if (build.getResult().isWorseThan(Result.UNSTABLE)
//                 || build.getResult().ordinal == Result.UNSTABLE.ordinal && !mApproveUnstable) {
//             logger.println("Bitbucket Approve: Skipping approval because build is " + build.getResult().toString());
//             return;
//         }


//         // String eOwner = build.getEnvironment(listener).expand(mOwner);
//         // String eSlug = build.getEnvironment(listener).expand(mSlug);
//         String eBitbucketPayload = build.getEnvironment(listener).expand(mBitbucketPayload);

//         BitbucketPullRequestPayloadModel pullRequestPayload = PayloadParser.parsePayload(build, listener,
//                 eBitbucketPayload);

//         // rest/api/1.0/projects/{projectKey}/repos/{repositorySlug}/pull-requests/{pullRequestId}/participants/{userSlug}
//         String url = String.format(
//                 "%s/rest/api/1.0/projects/%s/repos/%s/pull-requests/{pullRequestId}/participants/{userSlug}",
//                 mDescriptor.getBitbucketUrl(), pullRequestPayload.getProjectKey(),
//                 pullRequestPayload.getRepositorySlug(), mDescriptor.getUsername());

//         // String url = String.format("%s/repositories/%s/%s/commit/%s/approve",
//         // getDescriptor().getBitbucketUrl(),
//         // eOwner, eSlug, commitHash);

//         logger.println("Bitbucket Approve: " + url);

//         Request.Builder builder = new Request.Builder();
//         logger.println("Bitbucket Approve: Credentials Id: " + mDescriptor.getCredentialId());
//         Request request = builder.header("Authorization", mDescriptor.getBasicAuth()).url(url)
//                 .method("POST", RequestBody.create(null, "")).build();

//         try {
//             Response response = mApprover.getHttpClient().newCall(request).execute();

//             if (!isSuccessful(response)) {
//                 logger.println("Bitbucket Approve: " + response.code() + " - " + response.message());
//                 logger.println("Bitbucket Approve: " + response.body().string());
//             }
//         } catch (IOException e) {
//             e.printStackTrace(listener.getLogger());
//         }
//     }

// }