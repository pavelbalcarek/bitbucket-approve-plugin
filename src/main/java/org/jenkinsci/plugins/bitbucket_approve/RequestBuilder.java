package org.jenkinsci.plugins.bitbucket_approve;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class RequestBuilder {

    private final BitbucketApprover.DescriptorImpl mDescriptor;

    public RequestBuilder(BitbucketApprover.DescriptorImpl descriptor) {
        this.mDescriptor = descriptor;
    }

    public Request buildRequest(String urlPart, String method, String body) {
        return buildRequest(this.mDescriptor.getBitbucketUrl(),
                            this.mDescriptor.getBasicAuth(),
                            urlPart,
                            method,
                            body);
    }

    public Request buildRequest(String baseUrl, String username, String password,
                                String urlPart, String method, String body) {
        return buildRequest(this.mDescriptor.getBitbucketUrl(),
            Credentials.basic(username, password),
            urlPart,
            method,
            body);
    }

    public static Request buildRequest(String baseUrl, String basicAuth,
                                String urlPart, String method, String body) {
        String fullUrl = String.format("%s/%s", baseUrl, urlPart);

        RequestBody requestBody = null;
        if (body != null) {
            MediaType mediaJson = MediaType.parse("application/json; charset=utf-8");
            requestBody = RequestBody.create(mediaJson, body);
        }

        Request.Builder builder = new Request.Builder();
        Request request = builder.header("Authorization", basicAuth).url(fullUrl)
                .method(method, requestBody).build();

        return request;
    }
}