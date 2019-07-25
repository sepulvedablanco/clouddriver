/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.clouddriver.titus.deploy.handlers.steps

import com.netflix.spinnaker.clouddriver.aws.services.RegionScopedProviderFactory
import com.netflix.spinnaker.clouddriver.saga.models.Saga
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsRepository
import com.netflix.spinnaker.clouddriver.titus.TitusClientProvider
import com.netflix.spinnaker.clouddriver.titus.caching.utils.AwsLookupUtil
import com.netflix.spinnaker.clouddriver.titus.client.TitusClient
import com.netflix.spinnaker.clouddriver.titus.client.TitusRegion
import com.netflix.spinnaker.clouddriver.titus.client.model.Job
import com.netflix.spinnaker.clouddriver.titus.client.model.MigrationPolicy
import com.netflix.spinnaker.clouddriver.titus.credentials.NetflixTitusCredentials
import com.netflix.spinnaker.clouddriver.titus.deploy.description.TitusDeployDescription
import com.netflix.spinnaker.clouddriver.titus.deploy.events.Front50AppLoaded
import com.netflix.spinnaker.clouddriver.titus.deploy.events.TitusDeployPrepared
import com.netflix.spinnaker.clouddriver.titus.deploy.handlers.TitusDeployHandler
import com.netflix.spinnaker.config.AwsConfiguration
import spock.lang.Specification
import spock.lang.Subject

class PrepareTitusDeploymentStepSpec extends Specification {

  private static final String SAGA_NAME = "saga"
  private static final String SAGA_ID = "id"

  NetflixTitusCredentials netflixTitusCredentials = new NetflixTitusCredentials(
    'test', 'test', 'test', [new TitusRegion('us-east-1', 'test', 'http://foo', false, false, "blah", "blah", 7104, [])], 'test', 'test', 'test', 'test', false, '', 'mainvpc', [], "", false, false, false
  )

  AccountCredentialsRepository accountCredentialsRepository = Mock() {
    getOne("test") >> {
      return netflixTitusCredentials
    }
  }
  TitusClient titusClient = Mock(TitusClient)
  TitusClientProvider titusClientProvider = Mock() {
    getTitusClient(_, _) >> titusClient
  }
  AwsLookupUtil awsLookupUtil = Mock()
  RegionScopedProviderFactory regionScopedProviderFactory = Mock()
  AccountCredentialsProvider accountCredentialsProvider = Mock()
  AwsConfiguration.DeployDefaults deployDefaults = Mock()

  Saga saga = new Saga(SAGA_NAME, SAGA_ID, null, [], [])

  @Subject
  PrepareTitusDeploymentStep subject = new PrepareTitusDeploymentStep(
    accountCredentialsRepository,
    titusClientProvider,
    awsLookupUtil,
    regionScopedProviderFactory,
    accountCredentialsProvider,
    deployDefaults,
    Optional.empty()
  )

  def "merges source details when no asg name is provided"() {
    given:
    TitusDeployDescription description = createTitusDeployDescription()
    description.source = new TitusDeployDescription.Source(
      account: "test",
      region: "us-east-1",
      asgName: "spindemo-staging-highlander-v000",
      useSourceCapacity: true
    )

    and:
    Front50AppLoaded event = createFront50AppLoadedEvent(description)

    when:
    def result = subject.apply(event, saga)

    then:
    titusClient.findJobByName(_) >> {
      new Job(
        applicationName: "spindemo",
        digest: "abcd",
        securityGroups: ["hello"],
        instancesMin: 10,
        instancesMax: 10,
        instancesDesired: 10,
        labels: [sourceLabel: "sourceVal"],
        environment: ["HI": "hello"],
        containerAttributes: [sourceAttr: "sourceVal"],
        softConstraints: [],
        hardConstraints: []
      )
    }
    awsLookupUtil.securityGroupIdExists(_, _, _) >> true
    result.size() == 1
    result[0] instanceof TitusDeployPrepared

    and:
    def prepared = (TitusDeployPrepared) result[0]
    prepared.sagaName == SAGA_NAME
    prepared.sagaId == SAGA_ID
    prepared.front50App == event.front50App
    prepared.description.with {
      securityGroups == ["hello"]
      capacity.min == 10
      capacity.max == 10
      capacity.desired == 10
      labels == [sourceLabel: "sourceVal"]
      containerAttributes == [sourceAttr: "sourceVal"]
      env == ["HI": "hello"]
    }
  }

  private TitusDeployDescription createTitusDeployDescription() {
    return new TitusDeployDescription(
      application: "spindemo",
      capacity: new TitusDeployDescription.Capacity(
        desired: 1,
        max: 1,
        min: 1
      ),
      capacityGroup: "spindemo",
      containerAttributes: [:] as Map<String, String>,
      credentials: netflixTitusCredentials,
      env: [:] as Map<String, String>,
      freeFormDetails: "highlander",
      hardConstraints: [
        "UniqueHost",
        "ZoneBalance"
      ],
      iamProfile: "spindemoInstanceProfile",
      imageId: "spinnaker/basic:master-h47400.3aa8911",
      inService: true,
      labels: [:] as Map<String, String>,
      migrationPolicy: new MigrationPolicy(type: "systemDefault"),
      region: "us-east-1",
      resources: new TitusDeployDescription.Resources(
        allocateIpAddress: true,
        cpu: 1,
        disk: 5_000,
        gpu: 0,
        memory: 5_000,
        networkMbps: 128
      ),
      securityGroups: [],
      softConstraints: [],
      stack: "staging",
    )
  }

  private static Front50AppLoaded createFront50AppLoadedEvent(TitusDeployDescription description) {
    return createFront50AppLoadedEvent(description, null, false)
  }

  private static Front50AppLoaded createFront50AppLoadedEvent(
    TitusDeployDescription description,
    String email,
    boolean platformHealthOnly
  ) {
    return new Front50AppLoaded(
      SAGA_NAME,
      SAGA_ID,
      new TitusDeployHandler.Front50Application(email: email, platformHealthOnly: platformHealthOnly),
      description
    )
  }
}
