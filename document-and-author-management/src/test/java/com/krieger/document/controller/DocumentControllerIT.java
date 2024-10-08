package com.krieger.document.controller;

import com.krieger.author.entity.Author;
import com.krieger.author.models.AuthorRequest;
import com.krieger.author.repository.AuthorRepository;
import com.krieger.document.models.AllDocumentsResponse;
import com.krieger.document.models.DocumentRequest;
import com.krieger.document.models.DocumentResponse;
import com.krieger.document.repository.DocumentRepository;
import org.junit.jupiter.api.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DocumentControllerIT {

    @Autowired
    private AuthorRepository authorRepository;
    @Autowired
    private DocumentRepository documentRepository;

    @LocalServerPort
    private int port;

    private String documentUrl;

    private TestRestTemplate testRestTemplate;
    Author authorResponse;
    DocumentRequest documentRequest;

    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>(DockerImageName.parse("postgres:latest"))
            .withUsername("krieger")
            .withPassword("krieger")
            .withDatabaseName("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"))
            .withEmbeddedZookeeper();


    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL properties
        registry.add("spring.datasource.url", () -> postgreSQLContainer.getJdbcUrl());
        registry.add("spring.datasource.username", () -> postgreSQLContainer.getUsername());
        registry.add("spring.datasource.password", () -> postgreSQLContainer.getPassword());

        // Kafka properties
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @BeforeAll
    static void beforeAll() {
        postgreSQLContainer.start();
        kafka.start();
        System.setProperty("POSTGRES_PORT", postgreSQLContainer.getMappedPort(5432).toString());
        System.setProperty("KAFKA_SERVER", kafka.getBootstrapServers());
        System.setProperty("KAFKA_ADVERTISED_LISTENERS", kafka.getBootstrapServers());
    }

    @BeforeEach
    public void setUp() {
        testRestTemplate = new TestRestTemplate(
                new RestTemplateBuilder().basicAuthentication("krieger-document", "krieger-document")
        );
        documentUrl = "http://localhost:" + port + "/api/v1/documents";
        AuthorRequest authorRequest = new AuthorRequest("Sreekanth", "G");
        authorResponse = authorRepository.save(
                Author.builder()
                        .firstName(authorRequest.firstName())
                        .lastName(authorRequest.lastName())
                        .build()
        );
        documentRequest = new DocumentRequest("Document1", "Document Body1", Set.of(authorResponse.getId()), null);
    }

    @AfterEach
    public void cleanUp() {
        authorRepository.deleteAll();
        documentRepository.deleteAll();
    }

    @AfterAll
    static void afterAll() {
        postgreSQLContainer.stop();
        kafka.stop();
    }

    @Test
    void test_save_document_should_return_success_status_code_and_response() {
        // Set up manual authentication
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("krieger-document", "krieger-document",
                        AuthorityUtils.createAuthorityList("ROLE_DOCUMENT"))
        );
        ResponseEntity<DocumentResponse> responseEntity = testRestTemplate.postForEntity(documentUrl, documentRequest, DocumentResponse.class);
        assertEquals(HttpStatus.CREATED, responseEntity.getStatusCode());
        assertEquals(documentRequest.title(), Objects.requireNonNull(responseEntity.getBody()).getTitle());
        assertEquals(documentRequest.body(), responseEntity.getBody().getBody());
        // Clear the security context
        SecurityContextHolder.clearContext();
    }

    @Test
    void test_save_document_should_throw_bad_request_status_code_with_invalid_input_data() {
        documentRequest = new DocumentRequest(null, null, null, null);
        ResponseEntity<DocumentResponse> responseEntity = testRestTemplate.postForEntity(documentUrl, documentRequest, DocumentResponse.class);
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
    }

    @Test
    void test_update_document_should_return_success_status_code_and_response() {
        ResponseEntity<DocumentResponse> postForEntity = testRestTemplate.postForEntity(documentUrl, documentRequest, DocumentResponse.class);
        var document = postForEntity.getBody();
        documentRequest = new DocumentRequest("Updated Document1", "Updated Document Body1", Set.of(authorResponse.getId()), null);
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<DocumentRequest> entity = new HttpEntity<>(documentRequest, httpHeaders);
        assert document != null;
        ResponseEntity<DocumentResponse> responseEntity = testRestTemplate.exchange(
                documentUrl + "/" + document.getId(),
                HttpMethod.PUT,
                entity,
                DocumentResponse.class
        );
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(documentRequest.title(), Objects.requireNonNull(responseEntity.getBody()).getTitle());
        assertEquals(documentRequest.body(), responseEntity.getBody().getBody());
    }

    @Test
    void test_update_document_should_throw_error_status_code_with_same_document_as_reference_document() {
        ResponseEntity<DocumentResponse> postForEntity = testRestTemplate.postForEntity(documentUrl, documentRequest, DocumentResponse.class);
        var document = postForEntity.getBody();
        assert document != null;
        documentRequest = new DocumentRequest("Updated Document1", "Updated Document Body1", Set.of(authorResponse.getId()), Set.of(document.getId()));
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<DocumentRequest> entity = new HttpEntity<>(documentRequest, httpHeaders);
        ResponseEntity<DocumentResponse> responseEntity = testRestTemplate.exchange(
                documentUrl + "/" + document.getId(),
                HttpMethod.PUT,
                entity,
                DocumentResponse.class
        );
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
    }

    @Test
    void test_update_document_should_throw_bad_request_status_code_with_invalid_update_data() {
        documentRequest = new DocumentRequest(null, null, null, null);
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<DocumentRequest> entity = new HttpEntity<>(documentRequest, httpHeaders);
        ResponseEntity<DocumentResponse> responseEntity = testRestTemplate.exchange(
                documentUrl + "/1",
                HttpMethod.PUT,
                entity,
                DocumentResponse.class
        );
        assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
    }

    @Test
    void test_get_all_documents_should_return_success_status_code() {
        testRestTemplate.postForEntity(documentUrl, documentRequest, DocumentResponse.class);
        ResponseEntity<AllDocumentsResponse> responseEntity = testRestTemplate.getForEntity(documentUrl, AllDocumentsResponse.class);
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(1, Objects.requireNonNull(responseEntity.getBody()).content().size());
    }

    @Test
    void test_get_all_documents_should_return_empty_response_if_there_is_no_document_data_available() {
        ResponseEntity<AllDocumentsResponse> responseEntity = testRestTemplate.getForEntity(documentUrl, AllDocumentsResponse.class);
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(0, Objects.requireNonNull(responseEntity.getBody()).content().size());
    }

    @Test
    void test_get_document_by_id_should_return_success_status_code_with_document_data() {
        ResponseEntity<DocumentResponse> entity = testRestTemplate.postForEntity(documentUrl, documentRequest, DocumentResponse.class);
        assert entity != null;
        var document = entity.getBody();
        assert document != null;
        ResponseEntity<DocumentResponse> responseEntity = testRestTemplate.getForEntity(
                documentUrl + "/" + document.getId(),
                DocumentResponse.class
        );
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(document.getId(), Objects.requireNonNull(responseEntity.getBody()).getId());
        assertEquals(document.getTitle(), Objects.requireNonNull(responseEntity.getBody()).getTitle());
        assertEquals(document.getBody(), Objects.requireNonNull(responseEntity.getBody()).getBody());
    }

    @Test
    void test_get_document_by_id_throw_error_status_code_with_invalid_document_id() {
        ResponseEntity<DocumentResponse> responseEntity = testRestTemplate.getForEntity(
                documentUrl + "/124",
                DocumentResponse.class
        );
        assertEquals(HttpStatus.NOT_FOUND, responseEntity.getStatusCode());
    }

    @Test
    void test_delete_document_by_id_should_return_success_status_code_with_valid_document_id() {
        ResponseEntity<DocumentResponse> entity = testRestTemplate.postForEntity(documentUrl, documentRequest, DocumentResponse.class);
        assert entity != null;
        var document = entity.getBody();
        assert document != null;
        ResponseEntity<Void> responseEntity = testRestTemplate.exchange(
                documentUrl + "/" + document.getId(),
                HttpMethod.DELETE,
                null,
                Void.class
        );
        assertEquals(HttpStatus.NO_CONTENT, responseEntity.getStatusCode());
    }

    @Test
    void test_delete_document_by_id_throw_error_status_code_with_invalid_document_id() {
        ResponseEntity<Void> responseEntity = testRestTemplate.exchange(
                documentUrl + "/787",
                HttpMethod.DELETE,
                null,
                Void.class
        );
        assertEquals(HttpStatus.NOT_FOUND, responseEntity.getStatusCode());
    }

}
