package ch.unisg.worldpulse.streams.serialization.avro;

import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * Registryless Avro deserializer. Deserializes binary Avro data back into
 * SpecificRecord instances using the schema from the generated class.
 */
public class AvroDeserializer<T extends SpecificRecord> implements Deserializer<T> {

    private final Class<T> targetType;
    private final Schema schema;
    private final SpecificDatumReader<T> reader;

    public AvroDeserializer(Class<T> targetType) {
        this.targetType = targetType;
        try {
            this.schema = targetType.getDeclaredConstructor().newInstance().getSchema();
        } catch (Exception e) {
            throw new RuntimeException("Cannot get schema for " + targetType, e);
        }
        this.reader = new SpecificDatumReader<>(schema);
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {}

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(data, null);
            return reader.read(null, decoder);
        } catch (Exception e) {
            throw new RuntimeException("Error deserializing Avro record", e);
        }
    }

    @Override
    public void close() {}
}
