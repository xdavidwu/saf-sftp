package link.xdavidwu.saf.sftp;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.widget.Toast;
import android.util.Log;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.SFTPException;
import com.trilead.ssh2.SFTPv3Client;
import com.trilead.ssh2.SFTPv3DirectoryEntry;
import com.trilead.ssh2.SFTPv3FileAttributes;
import com.trilead.ssh2.SFTPv3FileHandle;
import com.trilead.ssh2.sftp.ErrorCodes;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Vector;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.util.io.PathUtils;

import link.xdavidwu.saf.AbstractUnixLikeDocumentsProvider;

public class SFTPDocumentsProvider extends AbstractUnixLikeDocumentsProvider {

	protected record ConnectionParams(String host, int port,
			String username, String password) {

		protected Connection connect() throws IOException {
			var connection = new Connection(host, port);
			connection.connect(null, 10000, 10000);
			if (!connection.authenticateWithPassword(username, password)) {
				connection.close();
				throw new IOException("Unable to authenticate");
			}
			return connection;
		}

		protected Uri getRootUri() {
			return new Uri.Builder().scheme("sftp")
				.authority(username + "@" + host + ":" + port).build();
		}
	}

	static {
		PathUtils.setUserHomeFolderResolver(() -> FileSystems.getDefault().getPath("/"));
	}
	private SshClient ssh = SshClient.setUpDefaultClient();
	private Connection connection;
	private ConnectionParams params;
	private StorageManager sm;
	private Handler ioHandler;
	private ToastThread lthread;

	private static final String TOAST_PREFIX = "SAF-SFTP: ";

	private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
		Root.COLUMN_ROOT_ID,
		Root.COLUMN_FLAGS,
		Root.COLUMN_ICON,
		Root.COLUMN_TITLE,
		Root.COLUMN_DOCUMENT_ID,
		Root.COLUMN_SUMMARY,
	};

	private static final String[] DEFAULT_DOC_PROJECTION = new String[] {
		Document.COLUMN_DOCUMENT_ID,
		Document.COLUMN_DISPLAY_NAME,
		Document.COLUMN_MIME_TYPE,
		Document.COLUMN_LAST_MODIFIED,
		Document.COLUMN_SIZE,
		Document.COLUMN_FLAGS,
	};

	@Override
	protected Uri getRootUri() {
		return params.getRootUri();
	}

	// use . as home, we don't show . it should be fine
	@Override
	protected String pathFromDocumentId(String documentId) {
		var path = super.pathFromDocumentId(documentId);
		if (path.startsWith("/./")) {
			return path.substring(1);
		} else if (path.equals("/.")) {
			return ".";
		}
		return path;
	}

	private IOException translateSFTPErrorCodes(IOException e) {
		if (e instanceof SFTPException s) {
			switch (s.getServerErrorCode()) {
			case ErrorCodes.SSH_FX_NO_SUCH_FILE:
			case ErrorCodes.SSH_FX_NO_SUCH_PATH:
				var fnf = new FileNotFoundException(s.getMessage());
				fnf.initCause(s);
				return fnf;
			default:
				return s;
			}
		}
		return e;
	}

	private <T> T translateSFTPErrorCodes(IOOperation<T> o)
			throws IOException {
		try {
			return o.execute();
		} catch (IOException e) {
			throw translateSFTPErrorCodes(e);
		}
	}

	@Override
	protected <T> Optional<T> ioWithCursor(Cursor c, IOOperation<T> o)
			throws FileNotFoundException {
		return super.ioWithCursor(c, () -> translateSFTPErrorCodes(o));
	}

	@Override
	protected <T> T ioToUnchecked(IOOperation<T> o)
			throws FileNotFoundException {
		return super.ioToUnchecked(() -> translateSFTPErrorCodes(o));
	}

	private void toast(String msg) {
		Message m = lthread.handler.obtainMessage();
		m.obj = TOAST_PREFIX + msg;
		lthread.handler.sendMessage(m);
	}

	private SharedPreferences.OnSharedPreferenceChangeListener loadConfig =
			(sp, key) -> {
		params = new ConnectionParams(
			sp.getString("host", ""),
			Integer.parseInt(sp.getString("port", "22")),
			sp.getString("username", ""),
			sp.getString("passwd", "")
		);
	};

	private SFTPv3Client getClient() throws IOException {
		// /shrug if we are somehow invoked on main thread
		StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);

		if (connection != null) {
			try {
				connection.ping();
				return new SFTPv3Client(connection);
			} catch (IOException e) {
				// continue with new connection attempt
			}
			connection.close();
			connection = null;
		}
		connection = params.connect();
		ssh.connect(params.username(), params.host(), params.port()).verify().getClientSession().close();
		return new SFTPv3Client(connection);
	}

	@Override
	public boolean onCreate() {
		sm = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
		lthread = new ToastThread(getContext());
		lthread.start();
		HandlerThread ioThread = new HandlerThread("IO thread");
		ioThread.start();
		ioHandler = new Handler(ioThread.getLooper());

		var sp = PreferenceManager.getDefaultSharedPreferences(getContext());
		sp.registerOnSharedPreferenceChangeListener(loadConfig);
		loadConfig.onSharedPreferenceChanged(sp, "");
		ssh.start();
		return true;
	}

	public ParcelFileDescriptor openDocument(String documentId, String mode,
			CancellationSignal cancellationSignal)
			throws FileNotFoundException {
		if (!"r".equals(mode)) {
			throw new UnsupportedOperationException(
				"Mode " + mode + " is not supported yet.");
		}
		SFTPv3Client sftp = ioToUnchecked(this::getClient);
		String filename = pathFromDocumentId(documentId);
		Log.v("SFTP", "od " + documentId);
		var file = ioToUnchecked(() -> sftp.openFileRO(filename));
		return ioToUnchecked(() -> sm.openProxyFileDescriptor(
			ParcelFileDescriptor.MODE_READ_ONLY,
			new SFTPProxyFileDescriptorCallback(sftp, file), ioHandler));
	}

	private Object[] getDocumentRow(String cols[], String documentId,
			SFTPv3FileAttributes stat) {
		var name = basename(documentId);
		var type = getType(stat.permissions, name);

		return Arrays.stream(cols).map(c -> switch (c) {
		case Document.COLUMN_DOCUMENT_ID -> documentId;
		case Document.COLUMN_DISPLAY_NAME -> name;
		case Document.COLUMN_MIME_TYPE -> type;
		case Document.COLUMN_SIZE -> stat.size;
		case Document.COLUMN_FLAGS -> {
			var flags = 0;
			if (typeSupportsMetadata(type)) {
				flags |= Document.FLAG_SUPPORTS_METADATA;
			}
			if (typeSupportsThumbnail(type)) {
				flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
			}
			yield flags;
		}
		default -> null;
		}).toArray();
	}

	protected interface SFTPQueryOperation {
		public void execute(SFTPv3Client sftp)
			throws FileNotFoundException, HaltWithCursorException;
	}

	protected Cursor performQuery(Cursor c, SFTPQueryOperation o)
			throws FileNotFoundException {
		return performQuery(c, () -> {
			var sftp = ioWithCursor(c, this::getClient)
				.orElseThrow(this::haltIt);
			try {
				o.execute(sftp);
			} finally {
				// XXX SFTPv3Client should be made AutoClosable
				sftp.close();
			}
		});
	}

	public Cursor queryChildDocuments(
			String parentDocumentId, String[] projection, String sortOrder)
			throws FileNotFoundException {
		var cols = projection != null ? projection : DEFAULT_DOC_PROJECTION;
		var result = new MatrixCursor(cols);

		return performQuery(result, sftp -> {
			var filename = pathFromDocumentId(parentDocumentId);
			// XXX raw container type @ SFTPv3Client::ls
			Vector<SFTPv3DirectoryEntry> entries =
				ioWithCursor(result, () -> sftp.ls(filename))
					.orElseThrow(this::haltIt);

			entries.stream()
				.filter(entry -> !entry.filename.equals(".") && !entry.filename.equals(".."))
				.map(entry -> {
					var documentId = parentDocumentId + '/' + entry.filename;
					return getDocumentRow(cols, documentId, entry.attributes);
				}).forEach(row -> result.addRow(row));
		});
	}

	public Cursor queryDocument(String documentId, String[] projection)
			throws FileNotFoundException {
		var cols = projection != null ? projection : DEFAULT_DOC_PROJECTION;
		var result = new MatrixCursor(cols);

		return performQuery(result, sftp -> {
			var path = pathFromDocumentId(documentId);
			var stat = ioWithCursor(result, () -> sftp.stat(path))
				.orElseThrow(this::haltIt);
			result.addRow(getDocumentRow(cols, documentId, stat));
		});
	}

	public Cursor queryRoots(String[] projection) {
		var cols = projection != null ? projection : DEFAULT_ROOT_PROJECTION;
		var result = new MatrixCursor(cols);

		var sp = PreferenceManager.getDefaultSharedPreferences(getContext());
		var mountpoint = sp.getString("mountpoint", ".");
		if (mountpoint.equals("")) {
			mountpoint = ".";
		}
		var documentId = documentIdFromPath(mountpoint);
		var rootUri = getRootUri();

		// TODO make title, summary more useful
		result.addRow(Arrays.stream(cols).map(c -> switch(c) {
		case Root.COLUMN_ROOT_ID -> rootUri.toString();
		case Root.COLUMN_DOCUMENT_ID -> documentId;
		case Root.COLUMN_FLAGS -> 0;
		case Root.COLUMN_TITLE -> documentId;
		case Root.COLUMN_ICON -> R.mipmap.sym_def_app_icon;
		case Root.COLUMN_SUMMARY -> "SFTP with user: " + params.username();
		default -> null;
		}).toArray());

		return result;
	}
}
