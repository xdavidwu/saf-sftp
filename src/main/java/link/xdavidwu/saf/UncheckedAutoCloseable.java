package link.xdavidwu.saf;

public record UncheckedAutoCloseable<T extends AutoCloseable>(T c)
		implements AutoCloseable {

	public void close() {
		try {
			c.close();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
