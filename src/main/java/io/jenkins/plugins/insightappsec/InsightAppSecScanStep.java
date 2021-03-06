package io.jenkins.plugins.insightappsec;

import io.jenkins.plugins.insightappsec.api.APIFactory;
import io.jenkins.plugins.insightappsec.api.HttpClientCache;
import io.jenkins.plugins.insightappsec.api.scan.ScanApi;
import io.jenkins.plugins.insightappsec.api.search.SearchApi;
import io.jenkins.plugins.insightappsec.credentials.InsightCredentialsHelper;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class InsightAppSecScanStep extends Builder implements SimpleBuildStep {

    private static final InsightCredentialsHelper INSIGHT_CREDENTIALS_HELPER = new InsightCredentialsHelper();
    private static final DurationStringParser DURATION_STRING_PARSER = new DurationStringParser();
    private static final ScanResultHandler SCAN_RESULT_HANDLER = new ScanResultHandler();

    private static final APIFactory API_FACTORY = new APIFactory(INSIGHT_CREDENTIALS_HELPER, HttpClientCache.SEARCH_API_HTTP_CLIENT,
                                                                                             HttpClientCache.APP_API_HTTP_CLIENT,
                                                                                             HttpClientCache.SCAN_API_HTTP_CLIENT);

    private final String region;
    private final String insightCredentialsId;
    private final String appId;
    private final String scanConfigId;
    private final String buildAdvanceIndicator;
    private final String vulnerabilityQuery;
    private final String maxScanPendingDuration;
    private final String maxScanExecutionDuration;
    private final boolean enableScanResults;

    @DataBoundConstructor
    public InsightAppSecScanStep(String region,
                                 String insightCredentialsId,
                                 String appId,
                                 String scanConfigId,
                                 String buildAdvanceIndicator,
                                 String vulnerabilityQuery,
                                 String maxScanPendingDuration,
                                 String maxScanExecutionDuration,
                                 boolean enableScanResults) {
        this.region = Region.fromString(region).name();
        this.insightCredentialsId = Util.fixEmptyAndTrim(insightCredentialsId);
        this.appId = Util.fixEmptyAndTrim(appId);
        this.scanConfigId = Util.fixEmptyAndTrim(scanConfigId);
        this.buildAdvanceIndicator = BuildAdvanceIndicator.fromString(buildAdvanceIndicator).name();
        this.vulnerabilityQuery = Util.fixEmptyAndTrim(vulnerabilityQuery);
        this.maxScanPendingDuration = Util.fixEmptyAndTrim(maxScanPendingDuration);
        this.maxScanExecutionDuration = Util.fixEmptyAndTrim(maxScanExecutionDuration);
        this.enableScanResults = enableScanResults;

        validateConfiguration();
    }

    public String getRegion() {
        return region;
    }

    public String getInsightCredentialsId() {
        return insightCredentialsId;
    }

    public String getAppId() {
        return appId;
    }

    public String getScanConfigId() {
        return scanConfigId;
    }

    public String getBuildAdvanceIndicator() {
        return buildAdvanceIndicator;
    }

    public String getVulnerabilityQuery() {
        return vulnerabilityQuery;
    }

    public String getMaxScanPendingDuration() {
        return maxScanPendingDuration;
    }

    public String getMaxScanExecutionDuration() {
        return maxScanExecutionDuration;
    }

    public boolean isEnableScanResults() {
        return enableScanResults;
    }

    @Override
    public void perform(Run<?, ?> run,
                        FilePath workspace,
                        Launcher launcher,
                        TaskListener listener) throws InterruptedException {
        InsightAppSecLogger logger = new InsightAppSecLogger(listener.getLogger());

        logger.log("Beginning IAS scan step with configuration: %n%s", this.toString());

        BuildAdvanceIndicator bai = BuildAdvanceIndicator.fromString(buildAdvanceIndicator);

        Optional<ScanResults> scanResults = newRunner(logger).run(scanConfigId,
                                                                  bai,
                                                                  vulnerabilityQuery);

        scanResults.ifPresent(sr -> SCAN_RESULT_HANDLER.handleScanResults(run, logger, bai, sr, enableScanResults));
    }

    // HELPERS

    private void validateConfiguration() {
        requireNonNull(region, "Region must not be null");
        requireNonNull(insightCredentialsId, "Insight Credentials ID must not be null");
        requireNonNull(scanConfigId, "Scan Config ID must not be null");
        requireNonNull(buildAdvanceIndicator, "Build Advance Indicator must not be null");
    }

    private InsightAppSecScanStepRunner newRunner(InsightAppSecLogger logger) {
        ScanApi scanApi = API_FACTORY.newScanApi(region, insightCredentialsId);
        SearchApi searchApi = API_FACTORY.newSearchApi(region, insightCredentialsId);

        return new InsightAppSecScanStepRunner(scanApi,
                                               searchApi,
                                               logger,
                                               newScanDurationHandler(scanApi, logger));
    }

    private ScanDurationHandler newScanDurationHandler(ScanApi scanApi,
                                                       InsightAppSecLogger logger) {
        Long maxScanPendingDuration = DURATION_STRING_PARSER.parseDurationString(this.maxScanPendingDuration);
        Long maxScanExecutionDuration = DURATION_STRING_PARSER.parseDurationString(this.maxScanExecutionDuration);

        return new ScanDurationHandler(BuildAdvanceIndicator.fromString(buildAdvanceIndicator),
                                       scanApi,
                                       logger,
                                       System.currentTimeMillis(),
                                       maxScanPendingDuration,
                                       maxScanExecutionDuration);
    }

    @Override
    public String toString() {
        return "{" + '\n' +
                "  region='" + region + '\'' + '\n' +
                "  insightCredentialsId='" + insightCredentialsId + '\'' + '\n' +
                "  appId='" + appId + '\'' + '\n' +
                "  scanConfigId='" + scanConfigId + '\'' + '\n' +
                "  buildAdvanceIndicator='" + buildAdvanceIndicator + '\'' + '\n' +
                "  vulnerabilityQuery='" + vulnerabilityQuery + '\'' + '\n' +
                "  maxScanPendingDuration='" + maxScanPendingDuration + '\'' + '\n' +
                "  maxScanExecutionDuration='" + maxScanExecutionDuration + '\'' + '\n' +
                "  enableScanResults=" + enableScanResults + '\n' +
                "}";
    }

    @Extension
    @Symbol("insightAppSec")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        private DescriptorHelper descriptorHelper = new DescriptorHelper(API_FACTORY,
                                                                         INSIGHT_CREDENTIALS_HELPER,
                                                                         DURATION_STRING_PARSER);

        public ListBoxModel doFillRegionItems() {
            return descriptorHelper.getRegionItems();
        }

        public FormValidation doCheckRegion(@QueryParameter String region) {
            return descriptorHelper.doCheckRequiredField(region);
        }

        public ListBoxModel doFillInsightCredentialsIdItems(@AncestorInPath Jenkins context) {
            return descriptorHelper.getInsightCredentialsIdItems(context);
        }

        public FormValidation doCheckInsightCredentialsId(@QueryParameter String insightCredentialsId) {
            return descriptorHelper.doCheckRequiredField(insightCredentialsId);
        }

        public ListBoxModel doFillAppIdItems(@QueryParameter String region,
                                             @QueryParameter String insightCredentialsId) {
            return descriptorHelper.getAppIdItems(region, insightCredentialsId);
        }

        public ListBoxModel doFillScanConfigIdItems(@QueryParameter String region,
                                                    @QueryParameter String insightCredentialsId,
                                                    @QueryParameter String appId) {
            return descriptorHelper.getScanConfigIdItems(region, insightCredentialsId, appId);
        }

        public FormValidation doCheckScanConfigId(@QueryParameter String scanConfigId) {
            return descriptorHelper.doCheckRequiredField(scanConfigId);
        }

        public ListBoxModel doFillBuildAdvanceIndicatorItems() {
            return descriptorHelper.getBuildAdvanceIndicatorItems();
        }

        public FormValidation doCheckBuildAdvanceIndicator(@QueryParameter String buildAdvanceIndicator) {
            return descriptorHelper.doCheckRequiredField(buildAdvanceIndicator);
        }

        public FormValidation doCheckVulnerabilityQuery() {
            // no actual validation, just return markup message
            return descriptorHelper.doCheckVulnerabilityQuery();
        }

        public FormValidation doCheckMaxScanPendingDuration(@QueryParameter String maxScanPendingDuration) {
            return descriptorHelper.doCheckMaxScanPendingDuration(maxScanPendingDuration);
        }

        public FormValidation doCheckMaxScanExecutionDuration(@QueryParameter String maxScanExecutionDuration) {
            return descriptorHelper.doCheckMaxScanExecutionDuration(maxScanExecutionDuration);
        }

        public FormValidation doCheckEnableScanResults() {
            // no actual validation, just return markup message
            return descriptorHelper.doCheckEnableScanResults();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.displayName();
        }
    }

}
