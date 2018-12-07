package com.rapid7.insightappsec.intg.jenkins;

import com.rapid7.insightappsec.intg.jenkins.api.Identifiable;
import com.rapid7.insightappsec.intg.jenkins.api.InsightAppSecLogger;
import com.rapid7.insightappsec.intg.jenkins.api.scan.ScanApi;
import com.rapid7.insightappsec.intg.jenkins.exception.ScanSubmissionFailedException;
import com.rapid7.insightappsec.intg.jenkins.mock.MockHttpResponse;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.rapid7.insightappsec.intg.jenkins.api.scan.Scan.ScanStatus.COMPLETE;
import static com.rapid7.insightappsec.intg.jenkins.api.scan.Scan.ScanStatus.PENDING;
import static com.rapid7.insightappsec.intg.jenkins.api.scan.Scan.ScanStatus.RUNNING;
import static com.rapid7.insightappsec.intg.jenkins.api.scan.ScanModels.aScan;
import static java.lang.String.format;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class InsightAppSecScanStepRunnerTest {

    @Mock
    private ScanApi scanApi;

    @Mock
    private InsightAppSecLogger logger;

    @Mock
    private ThreadHelper threadHelper;

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @InjectMocks
    private InsightAppSecScanStepRunner runner;

    @Before
    public void before() {
        runner.setLogger(logger);
    }

    @Test
    public void run_scanSubmit_201_advanceWhenSubmitted() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();

        HttpResponse response = MockHttpResponse.create(201, mockHeaders(scanId));
        given(scanApi.submitScan(scanConfigId)).willReturn(response);

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_SUBMITTED);

        // then
        verify(logger, times(1)).log("Scan submitted successfully");
    }

    @Test
    public void run_scanSubmit_201_advanceWhenStarted() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();

        HttpResponse submitResponse = MockHttpResponse.create(201, mockHeaders(scanId));
        given(scanApi.submitScan(scanConfigId)).willReturn(submitResponse);

        HttpResponse initialPoll = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(PENDING).build());
        HttpResponse subsequentPoll1 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(RUNNING).build());

        when(scanApi.getScan(scanId)).thenReturn(initialPoll)
                                     .thenReturn(subsequentPoll1);

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_STARTED);

        // then
        verify(logger, times(1)).log("Scan submitted successfully");
        verify(logger, times(1)).log("Using build advance indicator: '%s'", BuildAdvanceIndicator.SCAN_STARTED.getDisplayName());
        verify(logger, times(1)).log("Beginning polling for scan with id: %s", scanId);
        verify(logger, times(1)).log("Scan status: %s", PENDING);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", PENDING, RUNNING);
        verify(logger, times(1)).log("Desired scan status has been reached");

        verify(threadHelper, times(1)).sleep(TimeUnit.SECONDS.toMillis(15));
    }

    @Test
    public void run_scanSubmit_201_advanceWhenCompleted() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();

        HttpResponse submitResponse = MockHttpResponse.create(201, mockHeaders(scanId));
        given(scanApi.submitScan(scanConfigId)).willReturn(submitResponse);

        HttpResponse initialPoll = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(PENDING).build());
        HttpResponse subsequentPoll1 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(RUNNING).build());
        HttpResponse subsequentPoll2 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(COMPLETE).build());

        when(scanApi.getScan(scanId)).thenReturn(initialPoll)
                                     .thenReturn(subsequentPoll1)
                                     .thenReturn(subsequentPoll2);

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_COMPLETED);

        // then
        verify(logger, times(1)).log("Scan submitted successfully");
        verify(logger, times(1)).log("Using build advance indicator: '%s'", BuildAdvanceIndicator.SCAN_COMPLETED.getDisplayName());
        verify(logger, times(1)).log("Beginning polling for scan with id: %s", scanId);
        verify(logger, times(1)).log("Scan status: %s", PENDING);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", PENDING, RUNNING);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", RUNNING, COMPLETE);
        verify(logger, times(1)).log("Desired scan status has been reached");

        verify(threadHelper, times(2)).sleep(TimeUnit.SECONDS.toMillis(15));
    }

    @Test
    public void run_scanSubmit_advanceWhenCompleted_initialPollFails() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();

        HttpResponse submitResponse = MockHttpResponse.create(201, mockHeaders(scanId));
        given(scanApi.submitScan(scanConfigId)).willReturn(submitResponse);

        HttpResponse subsequentPoll1 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(RUNNING).build());
        HttpResponse subsequentPoll2 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(COMPLETE).build());

        when(scanApi.getScan(scanId)).thenThrow(new IOException())
                                     .thenReturn(subsequentPoll1)
                                     .thenReturn(subsequentPoll2);

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_COMPLETED);

        // then
        verify(logger, times(1)).log("Scan submitted successfully");
        verify(logger, times(1)).log("Using build advance indicator: '%s'", BuildAdvanceIndicator.SCAN_COMPLETED.getDisplayName());
        verify(logger, times(1)).log("Beginning polling for scan with id: %s", scanId);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", RUNNING, COMPLETE);
        verify(logger, times(1)).log("Desired scan status has been reached");

        verify(threadHelper, times(2)).sleep(TimeUnit.SECONDS.toMillis(15));
    }

    @Test
    public void run_scanSubmit_advanceWhenCompleted_initialAndFirstSubsequentPollFails() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();

        HttpResponse submitResponse = MockHttpResponse.create(201, mockHeaders(scanId));
        given(scanApi.submitScan(scanConfigId)).willReturn(submitResponse);

        HttpResponse subsequentPoll2 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(RUNNING).build());
        HttpResponse subsequentPoll3 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(COMPLETE).build());

        when(scanApi.getScan(scanId)).thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenReturn(subsequentPoll2)
                                     .thenReturn(subsequentPoll3);

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_COMPLETED);

        // then
        verify(logger, times(1)).log("Scan submitted successfully");
        verify(logger, times(1)).log("Using build advance indicator: '%s'", BuildAdvanceIndicator.SCAN_COMPLETED.getDisplayName());
        verify(logger, times(1)).log("Beginning polling for scan with id: %s", scanId);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", RUNNING, COMPLETE);
        verify(logger, times(1)).log("Desired scan status has been reached");

        verify(threadHelper, times(3)).sleep(TimeUnit.SECONDS.toMillis(15));
    }

    @Test
    public void run_scanSubmit_advanceWhenCompleted_firstFiveSubsequentPollsFail() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();

        HttpResponse submitResponse = MockHttpResponse.create(201, mockHeaders(scanId));
        given(scanApi.submitScan(scanConfigId)).willReturn(submitResponse);

        HttpResponse initialPoll = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(PENDING).build());
        HttpResponse subsequentPoll2 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(RUNNING).build());
        HttpResponse subsequentPoll3 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(COMPLETE).build());

        when(scanApi.getScan(scanId)).thenReturn(initialPoll)
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenReturn(subsequentPoll2)
                                     .thenReturn(subsequentPoll3);

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_COMPLETED);

        // then
        verify(logger, times(1)).log("Scan submitted successfully");
        verify(logger, times(1)).log("Using build advance indicator: '%s'", BuildAdvanceIndicator.SCAN_COMPLETED.getDisplayName());
        verify(logger, times(1)).log("Beginning polling for scan with id: %s", scanId);
        verify(logger, times(1)).log("Scan status: %s", PENDING);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", PENDING, RUNNING);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", RUNNING, COMPLETE);
        verify(logger, times(1)).log("Desired scan status has been reached");

        verify(threadHelper, times(7)).sleep(TimeUnit.SECONDS.toMillis(15));
    }

    @Test
    public void run_scanSubmit_advanceWhenCompleted_firstFiveSubsequentPollsFail_thenSuccess_thenNextFiveSubsequentPollsFail() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();

        HttpResponse submitResponse = MockHttpResponse.create(201, mockHeaders(scanId));
        given(scanApi.submitScan(scanConfigId)).willReturn(submitResponse);

        HttpResponse initialPoll = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(PENDING).build());
        HttpResponse subsequentPoll2 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(RUNNING).build());
        HttpResponse subsequentPoll3 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(COMPLETE).build());

        when(scanApi.getScan(scanId)).thenReturn(initialPoll)
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenReturn(subsequentPoll2)
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenReturn(subsequentPoll3);

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_COMPLETED);

        // then
        verify(logger, times(1)).log("Scan submitted successfully");
        verify(logger, times(1)).log("Using build advance indicator: '%s'", BuildAdvanceIndicator.SCAN_COMPLETED.getDisplayName());
        verify(logger, times(1)).log("Beginning polling for scan with id: %s", scanId);
        verify(logger, times(1)).log("Scan status: %s", PENDING);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", PENDING, RUNNING);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", RUNNING, COMPLETE);
        verify(logger, times(1)).log("Desired scan status has been reached");

        verify(threadHelper, times(12)).sleep(TimeUnit.SECONDS.toMillis(15));
    }

    @Test
    public void run_scanSubmit_advanceWhenCompleted_subsequentPollsFailAboveThreshold() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();

        HttpResponse submitResponse = MockHttpResponse.create(201, mockHeaders(scanId));
        given(scanApi.submitScan(scanConfigId)).willReturn(submitResponse);

        when(scanApi.getScan(scanId)).thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException());

        exception.expect(RuntimeException.class);
        exception.expectMessage("Scan polling has failed 21 times, aborting");

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_COMPLETED);

        // then
        // expected exception
    }

    @Test
    public void run_scanSubmit_advanceWhenCompleted_firstTwentyPollsFail_thenSuccess_thenNextTwoPollsFail_resetsFailureCount() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();
        String scanId = UUID.randomUUID().toString();

        HttpResponse submitResponse = MockHttpResponse.create(201, mockHeaders(scanId));
        given(scanApi.submitScan(scanConfigId)).willReturn(submitResponse);

        HttpResponse initialPoll = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(PENDING).build());
        HttpResponse subsequentPoll2 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(RUNNING).build());
        HttpResponse subsequentPoll3 = MockHttpResponse.create(200, aScan().scanConfig(new Identifiable(scanConfigId)).status(COMPLETE).build());

        when(scanApi.getScan(scanId)).thenReturn(initialPoll)
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenReturn(subsequentPoll2)
                                     .thenThrow(new IOException())
                                     .thenThrow(new IOException())
                                     .thenReturn(subsequentPoll3);

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_COMPLETED);

        // then
        verify(logger, times(1)).log("Scan submitted successfully");
        verify(logger, times(1)).log("Using build advance indicator: '%s'", BuildAdvanceIndicator.SCAN_COMPLETED.getDisplayName());
        verify(logger, times(1)).log("Beginning polling for scan with id: %s", scanId);
        verify(logger, times(1)).log("Scan status: %s", PENDING);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", PENDING, RUNNING);
        verify(logger, times(1)).log("Scan status has been updated from %s to %s", RUNNING, COMPLETE);
        verify(logger, times(1)).log("Desired scan status has been reached");

        verify(threadHelper, times(24)).sleep(TimeUnit.SECONDS.toMillis(15));
    }


    @Test
    public void run_scanSubmit_non201() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();

        HttpResponse response = MockHttpResponse.create(400);

        given(scanApi.submitScan(scanConfigId)).willReturn(response);

        exception.expect(ScanSubmissionFailedException.class);
        exception.expectMessage(format("Error occurred submitting scan. Response %n %s", response));

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_SUBMITTED);

        // then
        // exception expected
    }

    @Test
    public void run_scanSubmit_IOException() throws IOException, InterruptedException {
        // given
        String scanConfigId = UUID.randomUUID().toString();

        given(scanApi.submitScan(scanConfigId)).willThrow(new IOException());

        exception.expect(ScanSubmissionFailedException.class);
        exception.expectMessage("Error occurred submitting scan");

        // when
        runner.run(scanConfigId, BuildAdvanceIndicator.SCAN_COMPLETED);

        // then
        // exception expected
    }

    // TEST HELPERS

    private Header[] mockHeaders(String scanId) {
        Header[] headers = new Header[1];

        headers[0] = new BasicHeader(HttpHeaders.LOCATION, "http://test.com/" + scanId);

        return headers;
    }

}