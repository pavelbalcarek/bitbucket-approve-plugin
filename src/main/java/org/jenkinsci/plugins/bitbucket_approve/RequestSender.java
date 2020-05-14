package org.jenkinsci.plugins.bitbucket_approve;

import java.io.IOException;
import java.net.HttpURLConnection;

import org.apache.log4j.Level;

import hudson.model.TaskListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RequestSender {
    private static transient final LoggerInternal LOG = LoggerInternal.getLogger();

    final static void sendRequest(Request request, TaskListener listener, boolean ignoreSSL) throws IOException {
        OkHttpClient client = HttpClientUtils.getHttpClient(ignoreSSL);

        try {
            Response response = client.newCall(request).execute();

            if (!isSuccessful(response)) {
                String responseErrorText = formatResponseError(response);
                LOG.doLogAndPrint(responseErrorText, listener);
                throw new IOException("Failed with unexpected response: " + responseErrorText);
            }
        } catch (IOException e) {
            LOG.doLogAndPrint("Failed to send request", listener, Level.ERROR, e);
            throw e;
        }
    }

    /**
     * A 409 CONFLICT response means that we already approved this changeset. We do
     * not consider that an error.
     */
    private static boolean isSuccessful(Response response) throws IOException {
        return response.isSuccessful() || (response.code() == HttpURLConnection.HTTP_CONFLICT
                && response.body().string().contains("You already approved this changeset."));
    }

    private static String formatResponseError(Response response) {
        String responseBody = "<empty>";
        try {
            responseBody = response.body().string();
        } catch (Exception e) {
            // ignore
        }
        return String.format("%s (%s)\n%s", response.message(), response.code(), responseBody);
    }
}