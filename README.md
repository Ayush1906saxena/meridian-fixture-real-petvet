# Petvet

Records for a small vet practice, built over a weekend: owners register and see their pets, vets record visits,
and the practice can import pets from its old system (JSON, XML, or the old app's export file).

    mvn -q package && java -cp target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout) com.petvet.App
    mvn test

Plain JDK HTTP server and Jackson; data lives in memory.
