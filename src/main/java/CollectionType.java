public enum CollectionType {
    COLLECTION("Collection"),
    SET("Set"),
    LIST("List"),
    QUEUE("Queue");

    private final String name;

    CollectionType(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
