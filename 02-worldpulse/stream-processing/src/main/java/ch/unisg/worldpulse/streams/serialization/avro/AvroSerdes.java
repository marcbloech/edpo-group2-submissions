package ch.unisg.worldpulse.streams.serialization.avro;

import ch.unisg.worldpulse.streams.avro.AlertStats;
import ch.unisg.worldpulse.streams.avro.CorrelatedAlert;
import ch.unisg.worldpulse.streams.avro.CorrelatedAlertSummary;
import ch.unisg.worldpulse.streams.avro.EnrichedAlert;
import ch.unisg.worldpulse.streams.avro.NormalizedSignup;
import ch.unisg.worldpulse.streams.avro.PaymentSummary;
import ch.unisg.worldpulse.streams.avro.SymbolMetadata;
import ch.unisg.worldpulse.streams.avro.UserActivity;
import ch.unisg.worldpulse.streams.avro.UserDashboard;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;

/**
 * Factory class for registryless Avro Serdes.
 * One convenience method per Avro type, plus a generic method.
 */
public class AvroSerdes {

    public static <T extends SpecificRecord> Serde<T> forType(Class<T> clazz) {
        AvroSerializer<T> serializer = new AvroSerializer<>();
        AvroDeserializer<T> deserializer = new AvroDeserializer<>(clazz);
        return Serdes.serdeFrom(serializer, deserializer);
    }

    public static Serde<EnrichedAlert> EnrichedAlert() {
        return forType(EnrichedAlert.class);
    }

    public static Serde<NormalizedSignup> NormalizedSignup() {
        return forType(NormalizedSignup.class);
    }

    public static Serde<AlertStats> AlertStats() {
        return forType(AlertStats.class);
    }

    public static Serde<UserActivity> UserActivity() {
        return forType(UserActivity.class);
    }

    public static Serde<PaymentSummary> PaymentSummary() {
        return forType(PaymentSummary.class);
    }

    public static Serde<UserDashboard> UserDashboard() {
        return forType(UserDashboard.class);
    }

    public static Serde<SymbolMetadata> SymbolMetadata() {
        return forType(SymbolMetadata.class);
    }

    public static Serde<CorrelatedAlert> CorrelatedAlert() {
        return forType(CorrelatedAlert.class);
    }

    public static Serde<CorrelatedAlertSummary> CorrelatedAlertSummary() {
        return forType(CorrelatedAlertSummary.class);
    }
}
