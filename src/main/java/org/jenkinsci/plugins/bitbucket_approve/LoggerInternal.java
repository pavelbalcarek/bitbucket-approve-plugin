package org.jenkinsci.plugins.bitbucket_approve;

import java.io.PrintStream;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import hudson.model.TaskListener;

public class LoggerInternal {
    private static transient final Logger LOG = Logger.getLogger(BitbucketApprover.class.getName());
    private static LoggerInternal mLogger;

    public static LoggerInternal getLogger() {
        if (mLogger == null) {
            mLogger = new LoggerInternal();
        }

        return mLogger;
    }

    public void debug(String message) {
        doLogAndPrint(message, null, Level.DEBUG);
    }

    public void warn(String message) {
        doLogAndPrint(message, null, Level.WARN);
    }

    public void error(String message, Exception exception) {
        doLogAndPrint(message, null, Level.ERROR, exception);
    }

    public void doLogAndPrint(String message) {
        doLogAndPrint(message, null, Level.DEBUG);
    }

    public void doLogAndPrint(String message, TaskListener buildListener) {
        doLogAndPrint(message, buildListener, Level.DEBUG);
    }

    public void doLogAndPrint(String message, TaskListener buildListener, Level level) {
        doLogAndPrint(message, buildListener, level, null);
    }

    public void doLogAndPrint(String message, TaskListener buildListener, Level level, Exception exception) {
        if (StringUtils.isEmpty(message)) {
            return;
        }

        String approvePrefix = "Bitbucket Approve: ";
        if (message.startsWith(approvePrefix) == false) {
            message = approvePrefix + message;
        }

        if (buildListener != null) {
            PrintStream logger = buildListener.getLogger();
            logger.println(message);
        }

        if (level != null) {
            LOG.log(level, message, exception);
        }
    }
}