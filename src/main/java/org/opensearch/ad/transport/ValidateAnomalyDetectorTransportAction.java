/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.ad.transport;

import static org.opensearch.ad.rest.handler.AbstractAnomalyDetectorActionHandler.FEATURE_INVALID_MSG_PREFIX;
import static org.opensearch.ad.settings.AnomalyDetectorSettings.FILTER_BY_BACKEND_ROLES;
import static org.opensearch.ad.util.ParseUtils.checkFilterByBackendRoles;
import static org.opensearch.ad.util.ParseUtils.getUserContext;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionListener;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.ad.common.exception.ADValidationException;
import org.opensearch.ad.feature.SearchFeatureDao;
import org.opensearch.ad.indices.AnomalyDetectionIndices;
import org.opensearch.ad.model.AnomalyDetector;
import org.opensearch.ad.model.DetectorValidationIssue;
import org.opensearch.ad.rest.handler.AnomalyDetectorFunction;
import org.opensearch.ad.rest.handler.ValidateAnomalyDetectorActionHandler;
import org.opensearch.ad.settings.AnomalyDetectorSettings;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.commons.authuser.User;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.rest.RestRequest;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

public class ValidateAnomalyDetectorTransportAction extends
    HandledTransportAction<ValidateAnomalyDetectorRequest, ValidateAnomalyDetectorResponse> {
    private static final Logger logger = LogManager.getLogger(ValidateAnomalyDetectorTransportAction.class);

    private final Client client;
    private final ClusterService clusterService;
    private final NamedXContentRegistry xContentRegistry;
    private final AnomalyDetectionIndices anomalyDetectionIndices;
    private final SearchFeatureDao searchFeatureDao;
    private volatile Boolean filterByEnabled;

    @Inject
    public ValidateAnomalyDetectorTransportAction(
        Client client,
        ClusterService clusterService,
        NamedXContentRegistry xContentRegistry,
        Settings settings,
        AnomalyDetectionIndices anomalyDetectionIndices,
        ActionFilters actionFilters,
        TransportService transportService,
        SearchFeatureDao searchFeatureDao
    ) {
        super(ValidateAnomalyDetectorAction.NAME, transportService, actionFilters, ValidateAnomalyDetectorRequest::new);
        this.client = client;
        this.clusterService = clusterService;
        this.xContentRegistry = xContentRegistry;
        this.anomalyDetectionIndices = anomalyDetectionIndices;
        this.filterByEnabled = AnomalyDetectorSettings.FILTER_BY_BACKEND_ROLES.get(settings);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(FILTER_BY_BACKEND_ROLES, it -> filterByEnabled = it);
        this.searchFeatureDao = searchFeatureDao;
    }

    @Override
    protected void doExecute(Task task, ValidateAnomalyDetectorRequest request, ActionListener<ValidateAnomalyDetectorResponse> listener) {
        User user = getUserContext(client);
        AnomalyDetector anomalyDetector = request.getDetector();
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            resolveUserAndExecute(user, listener, detector -> validateExecute(request, user, context, listener));
        } catch (Exception e) {
            logger.error(e);
            listener.onFailure(e);
        }
    }

    private void resolveUserAndExecute(
        User requestedUser,
        ActionListener<ValidateAnomalyDetectorResponse> listener,
        Consumer<AnomalyDetector> function
    ) {
        try {
            // Check if user has backend roles
            // When filter by is enabled, block users validating detectors who do not have backend roles.
            if (filterByEnabled && !checkFilterByBackendRoles(requestedUser, listener)) {
                return;
            }
            // Validate Detector
            function.accept(null);
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    private void validateExecute(
        ValidateAnomalyDetectorRequest request,
        User user,
        ThreadContext.StoredContext storedContext,
        ActionListener<ValidateAnomalyDetectorResponse> listener
    ) {
        storedContext.restore();
        AnomalyDetector detector = request.getDetector();
        ActionListener<ValidateAnomalyDetectorResponse> validateListener = ActionListener.wrap(response -> {
            logger.debug("Result of validation process " + response);
            // forcing response to be empty
            listener.onResponse(new ValidateAnomalyDetectorResponse((DetectorValidationIssue) null));
        }, exception -> {
            if (exception instanceof ADValidationException) {
                // ADValidationException is converted as validation issues returned as response to user
                DetectorValidationIssue issue = parseADValidationException((ADValidationException) exception);
                listener.onResponse(new ValidateAnomalyDetectorResponse(issue));
                return;
            }
            listener.onFailure(exception);
        });
        checkIndicesAndExecute(detector.getIndices(), () -> {
            try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
                ValidateAnomalyDetectorActionHandler handler = new ValidateAnomalyDetectorActionHandler(
                    clusterService,
                    client,
                    validateListener,
                    anomalyDetectionIndices,
                    detector,
                    request.getRequestTimeout(),
                    request.getMaxSingleEntityAnomalyDetectors(),
                    request.getMaxMultiEntityAnomalyDetectors(),
                    request.getMaxAnomalyFeatures(),
                    RestRequest.Method.POST,
                    xContentRegistry,
                    user,
                    searchFeatureDao,
                    request.getTypeStr()
                );
                try {
                    handler.start();
                } catch (Exception exception) {
                    String errorMessage = String
                        .format(Locale.ROOT, "Unknown exception caught while validating detector %s", request.getDetector());
                    logger.error(errorMessage, exception);
                    listener.onFailure(exception);
                }
            }
        }, listener);
    }

    protected DetectorValidationIssue parseADValidationException(ADValidationException exception) {
        String originalErrorMessage = exception.getMessage();
        String errorMessage;
        Map<String, String> subIssues = null;
        Object suggestion = null;
        switch (exception.getType()) {
            case FEATURE_ATTRIBUTES:
                int firstLeftBracketIndex = originalErrorMessage.indexOf("[");
                int lastRightBracketIndex = originalErrorMessage.lastIndexOf("]");
                if (firstLeftBracketIndex != -1 && lastRightBracketIndex != -1) {
                    // if feature issue messages are between square brackets like
                    // [Feature has issue: A, Feature has issue: B]
                    errorMessage = originalErrorMessage.substring(firstLeftBracketIndex + 1, lastRightBracketIndex);
                    subIssues = getFeatureSubIssuesFromErrorMessage(errorMessage);
                } else {
                    // features having issue like over max feature limit, duplicate feature name, etc.
                    errorMessage = originalErrorMessage;
                }
                break;
            case NAME:
            case CATEGORY:
            case DETECTION_INTERVAL:
            case FILTER_QUERY:
            case TIMEFIELD_FIELD:
            case SHINGLE_SIZE_FIELD:
            case INDICES:
                errorMessage = originalErrorMessage;
                break;
            default:
                logger.warn(String.format(Locale.ROOT, "Invalid AD validation exception type: %s", exception));
                return null;
        }
        return new DetectorValidationIssue(exception.getAspect(), exception.getType(), errorMessage, subIssues, suggestion);
    }

    private Map<String, String> getFeatureSubIssuesFromErrorMessage(String errorMessage) {
        Map<String, String> result = new HashMap<String, String>();
        String featureSubIssueMessageSeparator = ", " + FEATURE_INVALID_MSG_PREFIX;

        String[] subIssueMessagesSuffix = errorMessage.split(featureSubIssueMessageSeparator);

        if (subIssueMessagesSuffix.length == 1) {
            result.put(getFeatureNameFromErrorMessage(errorMessage), getFeatureIssueFromErrorMessage(errorMessage));
            return result;
        }
        for (int i = 0; i < subIssueMessagesSuffix.length; i++) {
            if (i == 0) {
                // element at 0 doesn't have FEATURE_INVALID_MSG_PREFIX removed
                result
                    .put(
                        getFeatureNameFromErrorMessage(subIssueMessagesSuffix[i]),
                        getFeatureIssueFromErrorMessage(subIssueMessagesSuffix[i])
                    );
                continue;
            }
            String errorMessageForSingleFeature = FEATURE_INVALID_MSG_PREFIX + subIssueMessagesSuffix[i];
            result
                .put(
                    getFeatureNameFromErrorMessage(errorMessageForSingleFeature),
                    getFeatureIssueFromErrorMessage(errorMessageForSingleFeature)
                );
        }
        return result;
    }

    private String getFeatureNameFromErrorMessage(String errorMessage) {
        int colonIndex = errorMessage.indexOf(": ");
        // start index of featureName is colonIndex + 2, because there is space
        String featureName = errorMessage.substring(colonIndex + 2);
        return featureName;
    }

    private String getFeatureIssueFromErrorMessage(String errorMessage) {
        int colonIndex = errorMessage.indexOf(": ");
        // start index of message is 0, and its ending index is colonIndex
        String message = errorMessage.substring(0, colonIndex);

        return message;
    }

    private void checkIndicesAndExecute(
        List<String> indices,
        AnomalyDetectorFunction function,
        ActionListener<ValidateAnomalyDetectorResponse> listener
    ) {
        SearchRequest searchRequest = new SearchRequest()
            .indices(indices.toArray(new String[0]))
            .source(new SearchSourceBuilder().size(1).query(QueryBuilders.matchAllQuery()));
        client.search(searchRequest, ActionListener.wrap(r -> function.execute(), e -> {
            logger.error(e);
            listener.onFailure(e);
        }));
    }
}