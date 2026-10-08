// Runs once, on a fresh vt-mongo-data volume. The MongoDB Connector module refuses to connect to a database
// that doesn't exist yet (MongoDB normally creates databases lazily), so create it up front - along with
// the index the module's retention purge and "recent transcripts" query use.
const voice = db.getSiblingDB("voice");
voice.createCollection("transcriptions");
voice.transcriptions.createIndex({ timestamp: -1 }, { name: "timestamp_desc" });
