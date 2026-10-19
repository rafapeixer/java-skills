package example;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The one mapper of the service: immutable, thread-safe, and its caches warm up once. */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxDocumentLength(1_000_000L) // default: unlimited
                            .maxStringLength(100_000) // default: 100,000,000 chars
                            .maxNestingDepth(64) // default: 500
                            .build())
                    .build())
            // Off by default in Jackson 3. Our own contract: a misspelled field is a client bug.
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {}
}
