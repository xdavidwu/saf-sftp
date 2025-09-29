package link.xdavidwu.saf;

import android.database.Cursor;
import android.util.Log;
import android.os.Bundle;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;
import java.util.Optional;

public interface PerformsIO {
	public static final String TAG = "PerformsIO";

	// translate platform-specific IOException to java-native subtypes
	IOException translateIOException(IOException e);

	interface IOOperation<T> {
		T execute() throws IOException;
	}

	default <T> T io(IOOperation<T> o, Function<IOException, T> handle)
			throws FileNotFoundException {
		try {
			return o.execute();
		} catch (IOException|UncheckedIOException oe) {
			var e = translateIOException(
				oe instanceof UncheckedIOException u ? u.getCause() :
				(IOException) oe);
			if (e instanceof FileNotFoundException f) {
				throw f;
			}
			return handle.apply(e);
		}
	}

	default <T> T mustIO(IOOperation<T> o, Function<IOException, T> handle) {
		try {
			return o.execute();
		} catch (IOException|UncheckedIOException oe) {
			var e = translateIOException(
				oe instanceof UncheckedIOException u ? u.getCause() :
				(IOException) oe);
			return handle.apply(e);
		}
	}

	private <T> Function<IOException, Optional<T>> handleIOEViaCursor (
			Cursor c, String extraKey, String prefix) {
		return e -> {
			Log.e(TAG, "reporting error with cursor: ", e);

			var extras = c.getExtras();
			if (extras == Bundle.EMPTY) {
				extras = new Bundle();
			}

			var val = extras.getString(extraKey, "");
			var msg = prefix + Optional.ofNullable(e.getMessage())
				.flatMap(s -> s.length() != 0 ? Optional.of(s) : Optional.empty())
				.orElse(e.getClass().getName());
			extras.putString(extraKey,
				val.length() == 0 ? msg : val + "\n" + msg);
			c.setExtras(extras);
			return Optional.empty();
		};
	}

	default <T> Optional<T> ioWithCursor(Cursor c, IOOperation<T> o,
			String extraKey, String prefix) throws FileNotFoundException {
		return io(() -> Optional.ofNullable(o.execute()),
			handleIOEViaCursor(c, extraKey, prefix));
	}

	default <T> Optional<T> mustIOWithCursor(Cursor c, IOOperation<T> o,
			String extraKey, String prefix) {
		return mustIO(() -> Optional.ofNullable(o.execute()),
			handleIOEViaCursor(c, extraKey, prefix));
	}

	default <T> Optional<T> ioWithCursor(Cursor c, IOOperation<T> o)
			throws FileNotFoundException {
		return ioWithCursor(c, o, DocumentsContract.EXTRA_ERROR, "");
	}

	default <T> Optional<T> mustIOWithCursor(Cursor c, IOOperation<T> o) {
		return mustIOWithCursor(c, o, DocumentsContract.EXTRA_ERROR, "");
	}

	default <T> T ioToUnchecked(IOOperation<T> o)
			throws FileNotFoundException {
		return io(o, e -> {
			throw new UncheckedIOException(e);
		});
	}

	default <T> T mustIOToUnchecked(IOOperation<T> o) {
		return mustIO(o, e -> {
			throw new UncheckedIOException(e);
		});
	}

	// Helpers for ioWithCursor, to short circuit out with
	// ioWithCursor().orElseThrow(this::abortQuery)
	class AbortWithCursorException extends Exception {
		public static final long serialVersionUID = 42;
	}
	default AbortWithCursorException abortQuery() {
		return new AbortWithCursorException();
	}

	interface QueryOperation {
		public void execute()
			throws FileNotFoundException, AbortWithCursorException;
	}

	default Cursor performQuery(Cursor c, QueryOperation o)
			throws FileNotFoundException {
		try {
			o.execute();
		} catch (AbortWithCursorException e) {
		}
		return c;
	}
}
