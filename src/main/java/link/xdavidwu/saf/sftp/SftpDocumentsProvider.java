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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.util.io.PathUtils;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import link.xdavidwu.saf.AbstractUnixLikeDocumentsProvider;
import link.xdavidwu.saf.UncheckedAutoCloseable;

public class SftpDocumentsProvider extends AbstractUnixLikeDocumentsProvider {

	protected record ConnectionParams(String host, int port,
			String username, String password) {

		protected ClientSession connect() throws IOException {
			var session = ssh.connect(username, host, port)
				.verify(Duration.ofSeconds(3))
				.getClientSession();
			session.addPasswordIdentity(password);
			session.auth().verify(Duration.ofSeconds(3));
			return session;
		}

		protected Uri getRootUri() {
			return new Uri.Builder().scheme("sftp")
				.authority(username + "@" + host + ":" + port).build();
		}
	}

	private static final String HEARTBEAT_REQUEST = "keepalive@sftp.saf.xdavidwu.link";
	private static SshClient ssh;
	private ClientSession session;
	static {
		PathUtils.setUserHomeFolderResolver(() -> FileSystems.getDefault().getPath("/"));
		ssh = SshClient.setUpDefaultClient();
		ssh.start();
	}
	private ConnectionParams params;

	// do not start with a dot
	// DocumentsUI identify hidden files by presence of /. in documentId
	// (or display name starting with .)
	private static final String HOME_IDENTIFIER = ":HOME:";

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
		if (path.startsWith("/" + HOME_IDENTIFIER + "/")) {
			return path.substring(HOME_IDENTIFIER.length() + 2);
		} else if (path.equals("/" + HOME_IDENTIFIER)) {
			return ".";
		}
		return path;
	}

	@Override
	protected IOException translateIOException(IOException e) {
		if (e instanceof SftpException s) {
			switch (s.getStatus()) {
			case SftpConstants.SSH_FX_NO_SUCH_FILE:
			case SftpConstants.SSH_FX_NO_SUCH_PATH:
				var fnf = new FileNotFoundException(s.getMessage());
				fnf.initCause(s);
				return fnf;
			default:
				return s;
			}
		}
		return e;
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

	private SftpClient getClient() throws IOException {
		// /shrug if we are somehow invoked on main thread
		StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);

		if (session != null) {
			// org.apache.sshd.client.session.ClientConnectionService::sendHeartBeat
			var buf = session.createBuffer(
				SshConstants.SSH_MSG_GLOBAL_REQUEST,
				HEARTBEAT_REQUEST.length() + Byte.SIZE);
			buf.putString(HEARTBEAT_REQUEST);
			buf.putBoolean(true);
			try {
				session.request(HEARTBEAT_REQUEST, buf,
					Duration.ofSeconds(10));
				return SftpClientFactory.instance()
					.createSftpClient(session);
			} catch (IOException e) {
				// try new session
			}
			session.close();
			session = null;
		}
		session = params.connect();
		return SftpClientFactory.instance().createSftpClient(session);
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
		return true;
	}

	@Override
	public ParcelFileDescriptor openDocument(String documentId, String mode,
			CancellationSignal cancellationSignal)
			throws FileNotFoundException {
		if (!"r".equals(mode)) {
			throw new UnsupportedOperationException(
				"Mode " + mode + " is not supported yet.");
		}
		var sftp = ioToUnchecked(this::getClient);
		String filename = pathFromDocumentId(documentId);
		Log.v("SFTP", "od " + documentId);
		var file = ioToUnchecked(() -> sftp.open(filename));
		return ioToUnchecked(() -> sm.openProxyFileDescriptor(
			ParcelFileDescriptor.MODE_READ_ONLY,
			new SftpProxyFileDescriptorCallback(sftp, file),
			ioHandler));
	}

	private Object[] getDocumentRow(String cols[], String documentId,
			SftpClient.Attributes stat) {
		var name = basename(documentId);
		var type = getType(stat.getPermissions(), name);

		return Arrays.stream(cols).map(c -> switch (c) {
		case Document.COLUMN_DOCUMENT_ID -> documentId;
		case Document.COLUMN_DISPLAY_NAME -> name;
		case Document.COLUMN_MIME_TYPE -> type;
		case Document.COLUMN_SIZE -> stat.getSize();
		case Document.COLUMN_LAST_MODIFIED -> stat.getModifyTime().toMillis();
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

	protected interface SftpQueryOperation {
		public void execute(SftpClient sftp)
			throws FileNotFoundException, HaltWithCursorException;
	}

	protected Cursor performQuery(Cursor c, SftpQueryOperation o)
			throws FileNotFoundException {
		return performQuery(c, () -> {
			try (var sftp = new UncheckedAutoCloseable<SftpClient>(
					ioWithCursor(c, this::getClient)
					.orElseThrow(this::haltIt))) {
				o.execute(sftp.c());
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
			// lazy until iterator creation, openDir at iterator
			var entries = ioWithCursor(result,
				() -> sftp.readDir(filename).spliterator())
					.orElseThrow(this::haltIt);

			StreamSupport.stream(entries, false)
				.filter(entry -> !List.of(".", "..").contains(entry.getFilename()))
				.map(entry -> {
					var documentId = parentDocumentId + '/' + entry.getFilename();
					return getDocumentRow(cols, documentId, entry.getAttributes());
				}).forEach(row -> result.addRow(row));
		});
	}

	public Cursor queryDocument(String documentId, String[] projection)
			throws FileNotFoundException {
		var cols = projection != null ? projection : DEFAULT_DOC_PROJECTION;
		var result = new MatrixCursor(cols);

		return performQuery(result, sftp -> {
			var path = pathFromDocumentId(documentId);
			var stat = ioWithCursor(result, () -> sftp.lstat(path))
				.orElseThrow(this::haltIt);
			result.addRow(getDocumentRow(cols, documentId, stat));
		});
	}

	public Cursor queryRoots(String[] projection) {
		var cols = projection != null ? projection : DEFAULT_ROOT_PROJECTION;
		var result = new MatrixCursor(cols);

		var sp = PreferenceManager.getDefaultSharedPreferences(getContext());
		var mountpoint = sp.getString("mountpoint", ".");
		if (mountpoint.equals("") || mountpoint.equals(".")) {
			mountpoint = HOME_IDENTIFIER;
		} else if (mountpoint.startsWith("./")) {
			mountpoint = HOME_IDENTIFIER + mountpoint.substring(1);
		} else if (!mountpoint.startsWith("/")) {
			mountpoint = HOME_IDENTIFIER + "/" + mountpoint;
		}
		var documentId = documentIdFromPath(mountpoint);
		var rootUri = getRootUri();

		// TODO make title, summary more useful
		result.addRow(Arrays.stream(cols).map(c -> switch(c) {
		case Root.COLUMN_ROOT_ID -> rootUri.toString();
		case Root.COLUMN_DOCUMENT_ID -> documentId;
		case Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_IS_CHILD;
		case Root.COLUMN_TITLE -> documentId;
		case Root.COLUMN_ICON -> R.mipmap.sym_def_app_icon;
		case Root.COLUMN_SUMMARY -> "SFTP with user: " + params.username();
		default -> null;
		}).toArray());

		return result;
	}
}
