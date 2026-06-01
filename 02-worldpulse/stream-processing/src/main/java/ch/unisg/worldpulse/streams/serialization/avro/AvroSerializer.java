package ch.unisg.worldpulse.streams.serialization.avro;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Registryless Avro serializer. Serializes Avro SpecificRecord instances
 * to binary format without requiring a Schema Registry.
 */
public class AvroSerializer<T extends SpecificRecord> implements Serializer<T> {

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {}

    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null) {
            return null;
        }
        try {
            SpecificDatumWriter<T> writer = new SpecificDatumWriter<>(data.getSchema());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(out, null);
            writer.write(data, encoder);
            encoder.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Error serializing Avro record", e);
        }
    }

    @Override
    public void close() {}
}
