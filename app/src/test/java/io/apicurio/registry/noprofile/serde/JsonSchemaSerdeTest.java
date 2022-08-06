/*
 * Copyright 2022 Red Hat
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

package io.apicurio.registry.noprofile.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.apicurio.registry.rest.v2.beans.ArtifactReference;
import io.apicurio.registry.serde.SerdeConfig;
import io.apicurio.registry.serde.SerdeHeaders;
import io.apicurio.registry.support.Citizen;
import io.apicurio.registry.support.City;
import io.apicurio.registry.support.Country;
import io.apicurio.registry.support.Region;
import io.apicurio.registry.support.State;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.everit.json.schema.ValidationException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.apicurio.registry.AbstractResourceTestBase;
import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.rest.client.RegistryClientFactory;
import io.apicurio.registry.serde.jsonschema.JsonSchemaKafkaDeserializer;
import io.apicurio.registry.serde.jsonschema.JsonSchemaKafkaSerializer;
import io.apicurio.registry.serde.strategy.SimpleTopicIdStrategy;
import io.apicurio.registry.support.Person;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.utils.IoUtil;
import io.apicurio.registry.utils.tests.TestUtils;
import io.quarkus.test.junit.QuarkusTest;

/**
 * @author Fabian Martinez
 */
@QuarkusTest
public class JsonSchemaSerdeTest extends AbstractResourceTestBase {

    private RegistryClient restClient;

    @BeforeEach
    public void createIsolatedClient() {
        restClient = RegistryClientFactory.create(TestUtils.getRegistryV2ApiUrl());
    }

    @Test
    public void testJsonSchemaSerde() throws Exception {
        InputStream jsonSchema = getClass().getResourceAsStream("/io/apicurio/registry/util/json-schema.json");
        Assertions.assertNotNull(jsonSchema);

        String groupId = TestUtils.generateGroupId();
        String artifactId = generateArtifactId();

        final Integer globalId = createArtifact(groupId, artifactId, ArtifactType.JSON, IoUtil.toString(jsonSchema));

        this.waitForGlobalId(globalId);

        Person person = new Person("Ales", "Justin", 23);

        try (JsonSchemaKafkaSerializer<Person> serializer = new JsonSchemaKafkaSerializer<>(restClient, true);
             Deserializer<Person> deserializer = new JsonSchemaKafkaDeserializer<>(restClient, true)) {

            Map<String, Object> config = new HashMap<>();
            config.put(SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, groupId);
            config.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, SimpleTopicIdStrategy.class.getName());
            serializer.configure(config, false);

            deserializer.configure(Collections.emptyMap(), false);

            Headers headers = new RecordHeaders();
            byte[] bytes = serializer.serialize(artifactId, headers, person);

            person = deserializer.deserialize(artifactId, headers, bytes);

            Assertions.assertEquals("Ales", person.getFirstName());
            Assertions.assertEquals("Justin", person.getLastName());
            Assertions.assertEquals(23, person.getAge());

            person.setAge(-1);

            try {
                serializer.serialize(artifactId, new RecordHeaders(), person);
                Assertions.fail();
            } catch (Exception ignored) {
            }

            serializer.setValidationEnabled(false); // disable validation
            // create invalid person bytes
            bytes = serializer.serialize(artifactId, headers, person);

            try {
                deserializer.deserialize(artifactId, headers, bytes);
                Assertions.fail();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void testJsonSchemaSerdeHeaders() throws Exception {
        InputStream jsonSchema = getClass().getResourceAsStream("/io/apicurio/registry/util/json-schema.json");
        Assertions.assertNotNull(jsonSchema);

        String groupId = TestUtils.generateGroupId();
        String artifactId = generateArtifactId();

        Integer globalId = createArtifact(groupId, artifactId, ArtifactType.JSON, IoUtil.toString(jsonSchema));

        this.waitForGlobalId(globalId);

        Person person = new Person("Ales", "Justin", 23);

        try (JsonSchemaKafkaSerializer<Person> serializer = new JsonSchemaKafkaSerializer<>(restClient, true);
             Deserializer<Person> deserializer = new JsonSchemaKafkaDeserializer<>(restClient, true)) {

            Map<String, Object> config = new HashMap<>();
            config.put(SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, groupId);
            config.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, SimpleTopicIdStrategy.class.getName());
            serializer.configure(config, false);

            deserializer.configure(Collections.emptyMap(), false);

            Headers headers = new RecordHeaders();
            byte[] bytes = serializer.serialize(artifactId, headers, person);

            Assertions.assertNotNull(headers.lastHeader(SerdeHeaders.HEADER_VALUE_GLOBAL_ID));
            Header headerGlobalId = headers.lastHeader(SerdeHeaders.HEADER_VALUE_GLOBAL_ID);
            long id = ByteBuffer.wrap(headerGlobalId.value()).getLong();
            assertEquals(globalId.intValue(), Long.valueOf(id).intValue());

            Assertions.assertNotNull(headers.lastHeader(SerdeHeaders.HEADER_VALUE_MESSAGE_TYPE));
            Header headerMsgType = headers.lastHeader(SerdeHeaders.HEADER_VALUE_MESSAGE_TYPE);
            assertEquals(person.getClass().getName(), IoUtil.toString(headerMsgType.value()));

            person = deserializer.deserialize(artifactId, headers, bytes);

            Assertions.assertEquals("Ales", person.getFirstName());
            Assertions.assertEquals("Justin", person.getLastName());
            Assertions.assertEquals(23, person.getAge());
        }

    }

    @Test
    public void testJsonSchemaSerdeMagicByte() throws Exception {

        InputStream jsonSchema = getClass().getResourceAsStream("/io/apicurio/registry/util/json-schema-with-java-type.json");
        Assertions.assertNotNull(jsonSchema);

        String groupId = TestUtils.generateGroupId();
        String artifactId = generateArtifactId();

        Integer globalId = createArtifact(groupId, artifactId, ArtifactType.JSON, IoUtil.toString(jsonSchema));

        this.waitForGlobalId(globalId);

        Person person = new Person("Ales", "Justin", 23);

        try (JsonSchemaKafkaSerializer<Person> serializer = new JsonSchemaKafkaSerializer<>(restClient, true);
             Deserializer<Person> deserializer = new JsonSchemaKafkaDeserializer<>(restClient, true)) {

            Map<String, Object> config = new HashMap<>();
            config.put(SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, groupId);
            config.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, SimpleTopicIdStrategy.class.getName());
            serializer.configure(config, false);

            deserializer.configure(Collections.emptyMap(), false);

            byte[] bytes = serializer.serialize(artifactId, person);

            TestUtils.waitForSchema(schemaGlobalId -> {
                assertEquals(globalId.intValue(), schemaGlobalId.intValue());
                return true;
            }, bytes);

            person = deserializer.deserialize(artifactId, bytes);

            Assertions.assertEquals("Ales", person.getFirstName());
            Assertions.assertEquals("Justin", person.getLastName());
            Assertions.assertEquals(23, person.getAge());
        }
    }

    @Test
    public void testJsonSchemaSerdeWithReferences() throws Exception {
        InputStream regionSchema = getClass().getResourceAsStream("/io/apicurio/registry/util/region.json");
        InputStream countrySchema = getClass().getResourceAsStream("/io/apicurio/registry/util/country.json");
        InputStream stateSchema = getClass().getResourceAsStream("/io/apicurio/registry/util/state.json");
        InputStream citySchema = getClass().getResourceAsStream("/io/apicurio/registry/util/city.json");
        InputStream citizenSchema = getClass().getResourceAsStream("/io/apicurio/registry/util/citizen.json");

        Assertions.assertNotNull(regionSchema);
        Assertions.assertNotNull(countrySchema);
        Assertions.assertNotNull(stateSchema);
        Assertions.assertNotNull(citizenSchema);
        Assertions.assertNotNull(citySchema);

        final String groupId = TestUtils.generateGroupId();

        final String regionArtifactId = generateArtifactId();
        final Integer regionGlobalId = createArtifact(groupId, regionArtifactId, ArtifactType.JSON, IoUtil.toString(regionSchema));
        this.waitForGlobalId(regionGlobalId);

        final ArtifactReference regionReference = new ArtifactReference();
        regionReference.setVersion("1");
        regionReference.setGroupId(groupId);
        regionReference.setArtifactId(regionArtifactId);
        regionReference.setName("region.json");

        final String countryArtifactId = generateArtifactId();
        final Integer countryGlobalId = createArtifactWithReferences(groupId, countryArtifactId, ArtifactType.JSON, IoUtil.toString(countrySchema), Collections.singletonList(regionReference));
        this.waitForGlobalId(countryGlobalId);

        final ArtifactReference countryReference = new ArtifactReference();
        countryReference.setVersion("1");
        countryReference.setGroupId(groupId);
        countryReference.setArtifactId(countryArtifactId);
        countryReference.setName("country.json");

        final String stateArtifactId = generateArtifactId();
        final Integer stateGlobalId = createArtifactWithReferences(groupId, stateArtifactId, ArtifactType.JSON, IoUtil.toString(stateSchema), Collections.singletonList(countryReference));
        this.waitForGlobalId(stateGlobalId);

        final ArtifactReference stateReference = new ArtifactReference();
        stateReference.setVersion("1");
        stateReference.setGroupId(groupId);
        stateReference.setArtifactId(stateArtifactId);
        stateReference.setName("state.json");

        String cityArtifactId = generateArtifactId();
        final Integer cityGlobalId = createArtifactWithReferences(groupId, cityArtifactId, ArtifactType.JSON, IoUtil.toString(citySchema), Collections.singletonList(stateReference));
        this.waitForGlobalId(cityGlobalId);

        final ArtifactReference cityReference = new ArtifactReference();
        cityReference.setVersion("1");
        cityReference.setGroupId(groupId);
        cityReference.setArtifactId(cityArtifactId);
        cityReference.setName("city.json");

        final String artifactId = generateArtifactId();
        final Integer globalId = createArtifactWithReferences(groupId, artifactId, ArtifactType.JSON, IoUtil.toString(citizenSchema), Collections.singletonList(cityReference));
        this.waitForGlobalId(globalId);

        final Region northAmerica = new Region("North America", "NA");
        final Country usa = new Country("United States of America", "USA", northAmerica);

        final State newYorkState = new State("New York", "NY", usa);
        final City newYorkCity = new City("New York", 10001, newYorkState);
        final Citizen citizen = new Citizen("Carles", "Arnal", 23, newYorkCity);

        try (JsonSchemaKafkaSerializer<Citizen> serializer = new JsonSchemaKafkaSerializer<>(restClient, true);
             Deserializer<Citizen> deserializer = new JsonSchemaKafkaDeserializer<>(restClient, true)) {

            Map<String, Object> config = new HashMap<>();
            config.put(SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, groupId);
            config.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, SimpleTopicIdStrategy.class.getName());
            serializer.configure(config, false);

            deserializer.configure(Collections.emptyMap(), false);

            Headers headers = new RecordHeaders();
            byte[] bytes = serializer.serialize(artifactId, headers, citizen);

            final Citizen deserializedCitizen = deserializer.deserialize(artifactId, headers, bytes);

            Assertions.assertEquals("Carles", deserializedCitizen.getFirstName());
            Assertions.assertEquals("Arnal", deserializedCitizen.getLastName());
            Assertions.assertEquals(23, deserializedCitizen.getAge());
            Assertions.assertEquals("New York", deserializedCitizen.getCity().getName());

            citizen.setAge(-1);

            assertThrows(ValidationException.class, () -> serializer.serialize(artifactId, new RecordHeaders(), citizen));

            State missouriState = new State("Missouri", "MO", usa);

            citizen.setAge(23);
            final City kansasCity = new City("Kansas CIty", -31, missouriState);
            citizen.setCity(kansasCity);

            assertThrows(ValidationException.class, () -> serializer.serialize(artifactId, new RecordHeaders(), citizen));

        }
    }
}
