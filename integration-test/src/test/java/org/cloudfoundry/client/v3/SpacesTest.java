/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cloudfoundry.client.v3;

import org.cloudfoundry.AbstractIntegrationTest;
import org.cloudfoundry.CloudFoundryVersion;
import org.cloudfoundry.IfCloudFoundryVersion;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v3.isolationsegments.AddIsolationSegmentOrganizationEntitlementRequest;
import org.cloudfoundry.client.v3.isolationsegments.AddIsolationSegmentOrganizationEntitlementResponse;
import org.cloudfoundry.client.v3.isolationsegments.CreateIsolationSegmentRequest;
import org.cloudfoundry.client.v3.isolationsegments.CreateIsolationSegmentResponse;
import org.cloudfoundry.client.v3.spaces.AssignSpaceIsolationSegmentRequest;
import org.cloudfoundry.client.v3.spaces.AssignSpaceIsolationSegmentResponse;
import org.cloudfoundry.client.v3.spaces.CreateSpaceRequest;
import org.cloudfoundry.client.v3.spaces.CreateSpaceResponse;
import org.cloudfoundry.client.v3.spaces.GetSpaceIsolationSegmentRequest;
import org.cloudfoundry.client.v3.spaces.ListSpacesRequest;
import org.cloudfoundry.client.v3.spaces.SpaceRelationships;
import org.cloudfoundry.client.v3.spaces.SpaceResource;
import org.cloudfoundry.client.v3.spaces.UpdateSpaceRequest;
import org.cloudfoundry.util.PaginationUtils;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.cloudfoundry.util.tuple.TupleUtils.function;

@IfCloudFoundryVersion(greaterThanOrEqualTo = CloudFoundryVersion.PCF_1_12)
public final class SpacesTest extends AbstractIntegrationTest {

    @Autowired
    private CloudFoundryClient cloudFoundryClient;

    @Autowired
    private Mono<String> organizationId;

    @Test
    public void assignIsolationSegment() {
        String isolationSegmentName = this.nameFactory.getIsolationSegmentName();
        String spaceName = this.nameFactory.getSpaceName();

        this.organizationId
            .flatMap(organizationId -> Mono.zip(
                createIsolationSegmentId(this.cloudFoundryClient, isolationSegmentName, organizationId),
                createSpaceId(this.cloudFoundryClient, organizationId, spaceName))
            )
            .flatMap(function((isolationSegmentId, spaceId) -> Mono.zip(
                Mono.just(isolationSegmentId),
                this.cloudFoundryClient.spacesV3()
                    .assignIsolationSegment(AssignSpaceIsolationSegmentRequest.builder()
                        .data(Relationship.builder()
                            .id(isolationSegmentId)
                            .build())
                        .spaceId(spaceId)
                        .build())
                    .map(response -> response.getData().getId())
            )))
            .as(StepVerifier::create)
            .consumeNextWith(tupleEquality())
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void create() {
        String spaceName = this.nameFactory.getSpaceName();

        this.organizationId
            .flatMap(organizationId -> this.cloudFoundryClient.spacesV3()
                .create(CreateSpaceRequest.builder()
                    .name(spaceName)
                    .relationships(SpaceRelationships.builder()
                        .organization(ToOneRelationship.builder()
                            .data(Relationship.builder()
                                .id(organizationId)
                                .build())
                            .build())
                        .build())
                    .build()))
            .then(requestListSpaces(this.cloudFoundryClient, spaceName)
                .single())
            .as(StepVerifier::create)
            .expectNextCount(1)
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void getIsolationSegment() {
        String isolationSegmentName = this.nameFactory.getIsolationSegmentName();
        String spaceName = this.nameFactory.getSpaceName();

        this.organizationId
            .flatMap(organizationId -> Mono.zip(
                createIsolationSegmentId(this.cloudFoundryClient, isolationSegmentName, organizationId),
                createSpaceId(this.cloudFoundryClient, organizationId, spaceName))
            )
            .delayUntil(function((isolationSegmentId, spaceId) -> requestAssignIsolationSegment(this.cloudFoundryClient, isolationSegmentId, spaceId)))
            .flatMap(function((isolationSegmentId, spaceId) -> Mono.zip(
                Mono.just(isolationSegmentId),
                this.cloudFoundryClient.spacesV3()
                    .getIsolationSegment(GetSpaceIsolationSegmentRequest.builder()
                        .spaceId(spaceId)
                        .build())
                    .map(response -> response.getData().getId()))))
            .as(StepVerifier::create)
            .consumeNextWith(tupleEquality())
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void update() {
        String spaceName = this.nameFactory.getSpaceName();

        this.organizationId
            .flatMap(organizationId -> requestCreateSpace(this.cloudFoundryClient, organizationId, spaceName))
            .flatMap(resp -> this.cloudFoundryClient.spacesV3().update(UpdateSpaceRequest.builder()
                .spaceId(resp.getId())
                .metadata(Metadata.builder()
                    .annotation("annotationKey", "annotationValue")
                    .label("labelKey", "labelValue")
                    .build())
                .build()))
            .thenMany(PaginationUtils.requestClientV3Resources(page -> this.cloudFoundryClient.spacesV3()
                .list(ListSpacesRequest.builder()
                    .page(page)
                    .build())))
            .filter(resource -> spaceName.equals(resource.getName()) &&
                "annotationValue".equals(resource.getMetadata().getAnnotations().get("annotationKey")) &&
                "labelValue".equals(resource.getMetadata().getAnnotations().get("labelKey")))
            .as(StepVerifier::create)
            .expectNextCount(1)
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void list() {
        String spaceName = this.nameFactory.getSpaceName();

        this.organizationId
            .flatMap(organizationId -> requestCreateSpace(this.cloudFoundryClient, organizationId, spaceName))
            .thenMany(PaginationUtils.requestClientV3Resources(page -> this.cloudFoundryClient.spacesV3()
                .list(ListSpacesRequest.builder()
                    .page(page)
                    .build())))
            .filter(resource -> spaceName.equals(resource.getName()))
            .as(StepVerifier::create)
            .expectNextCount(1)
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void listFilterByName() {
        String spaceName = this.nameFactory.getSpaceName();

        this.organizationId
            .flatMap(organizationId -> requestCreateSpace(this.cloudFoundryClient, organizationId, spaceName))
            .thenMany(PaginationUtils.requestClientV3Resources(page -> this.cloudFoundryClient.spacesV3()
                .list(ListSpacesRequest.builder()
                    .name(spaceName)
                    .build()))
                .single())
            .map(SpaceResource::getName)
            .as(StepVerifier::create)
            .expectNext(spaceName)
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    @Test
    public void listFilterByOrganization() {
        String spaceName = this.nameFactory.getSpaceName();

        this.organizationId
            .delayUntil(organizationId -> requestCreateSpace(this.cloudFoundryClient, organizationId, spaceName))
            .flatMapMany(organizationId -> PaginationUtils
                .requestClientV3Resources(page -> this.cloudFoundryClient.spacesV3()
                    .list(ListSpacesRequest.builder()
                        .organizationId(organizationId)
                        .page(page)
                        .build())))
            .filter(resource -> spaceName.equals(resource.getName()))
            .as(StepVerifier::create)
            .expectNextCount(1)
            .expectComplete()
            .verify(Duration.ofMinutes(5));
    }

    private static Mono<String> createIsolationSegmentId(CloudFoundryClient cloudFoundryClient, String isolationSegmentName, String organizationId) {
        return requestCreateIsolationSegment(cloudFoundryClient, isolationSegmentName)
            .map(CreateIsolationSegmentResponse::getId)
            .delayUntil(isolationSegmentId -> requestAddIsolationSegmentOrganizationEntitlement(cloudFoundryClient, isolationSegmentId, organizationId));
    }

    private static Mono<String> createSpaceId(CloudFoundryClient cloudFoundryClient, String organizationId, String spaceName) {
        return requestCreateSpace(cloudFoundryClient, organizationId, spaceName)
            .map(CreateSpaceResponse::getId);
    }

    private static Mono<AddIsolationSegmentOrganizationEntitlementResponse> requestAddIsolationSegmentOrganizationEntitlement(CloudFoundryClient cloudFoundryClient, String isolationSegmentId,
                                                                                                                              String organizationId) {
        return cloudFoundryClient.isolationSegments()
            .addOrganizationEntitlement(AddIsolationSegmentOrganizationEntitlementRequest.builder()
                .isolationSegmentId(isolationSegmentId)
                .data(Relationship.builder()
                    .id(organizationId)
                    .build())
                .build());
    }

    private static Mono<AssignSpaceIsolationSegmentResponse> requestAssignIsolationSegment(CloudFoundryClient cloudFoundryClient, String isolationSegmentId, String spaceId) {
        return cloudFoundryClient.spacesV3()
            .assignIsolationSegment(AssignSpaceIsolationSegmentRequest.builder()
                .data(Relationship.builder()
                    .id(isolationSegmentId)
                    .build())
                .spaceId(spaceId)
                .build());
    }

    private static Mono<CreateIsolationSegmentResponse> requestCreateIsolationSegment(CloudFoundryClient cloudFoundryClient, String isolationSegmentName) {
        return cloudFoundryClient.isolationSegments()
            .create(CreateIsolationSegmentRequest.builder()
                .name(isolationSegmentName)
                .build());
    }

    private static Mono<CreateSpaceResponse> requestCreateSpace(CloudFoundryClient cloudFoundryClient, String organizationId, String spaceName) {
        return cloudFoundryClient.spacesV3()
            .create(CreateSpaceRequest.builder()
                .name(spaceName)
                .relationships(SpaceRelationships.builder()
                    .organization(ToOneRelationship.builder()
                        .data(Relationship.builder()
                            .id(organizationId)
                            .build())
                        .build())
                    .build())
                .build());
    }

    private static Flux<SpaceResource> requestListSpaces(CloudFoundryClient cloudFoundryClient, String spaceName) {
        return PaginationUtils.requestClientV3Resources(page -> cloudFoundryClient.spacesV3()
            .list(ListSpacesRequest.builder()
                .name(spaceName)
                .page(page)
                .build()));
    }

}
