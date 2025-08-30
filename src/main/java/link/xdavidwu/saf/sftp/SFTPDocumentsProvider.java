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
import com.trilead.ssh2.SFTPv3Client;
import com.trilead.ssh2.SFTPv3DirectoryEntry;
import com.trilead.ssh2.SFTPv3FileAttributes;
import com.trilead.ssh2.SFTPv3FileHandle;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.Vector;

import link.xdavidwu.saf.AbstractUnixLikeDocumentsProvider;

// TODO SFTPv3Client should be made AutoClosable

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

	private void fillStatRow(MatrixCursor.RowBuilder row, String name,
			SFTPv3FileAttributes stat) {
		row.add(Document.COLUMN_DISPLAY_NAME, name);
		row.add(Document.COLUMN_MIME_TYPE, getType(stat.permissions, name));
		row.add(Document.COLUMN_SIZE, stat.size);
		row.add(Document.COLUMN_LAST_MODIFIED, stat.mtime * 1000);
		row.add(Document.COLUMN_FLAGS, 0);
	}

	public Cursor queryChildDocuments(
		String parentDocumentId, String[] projection, String sortOrder)
			throws FileNotFoundException {
		MatrixCursor result =
			new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v("SFTP", "qcf " + parentDocumentId);
		var maybeSftp = ioWithCursor(result, this::getClient);
		if (maybeSftp.isEmpty()) {
			return result;
		}
		var sftp = maybeSftp.get();

		String filename = pathFromDocumentId(parentDocumentId);
		// XXX raw container type @ SFTPv3Client::ls
		Optional<Vector<SFTPv3DirectoryEntry>> entries = ioWithCursor(result, () -> sftp.ls(filename));
		sftp.close();
		if (entries.isEmpty()) {
			return result;
		}
		for (SFTPv3DirectoryEntry entry : entries.get()) {
			if (entry.filename.equals(".") || entry.filename.equals(".."))
				continue;
			MatrixCursor.RowBuilder row = result.newRow();
			row.add(Document.COLUMN_DOCUMENT_ID,
				parentDocumentId + '/' + entry.filename);
			fillStatRow(row, entry.filename, entry.attributes);
		}
		return result;
	}

	public Cursor queryDocument(String documentId, String[] projection)
			throws FileNotFoundException {
		MatrixCursor result = new MatrixCursor(
				projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v("SFTP", "qf " + documentId);
		var maybeSftp = ioWithCursor(result, this::getClient);
		if (maybeSftp.isEmpty()) {
			return result;
		}
		var sftp = maybeSftp.get();

		String filename = pathFromDocumentId(documentId);
		String basename = filename.substring(filename.lastIndexOf("/") + 1);
		var stat = ioWithCursor(result, () -> sftp.stat(filename));
		sftp.close();
		if (stat.isEmpty()) {
			return result;
		}
		MatrixCursor.RowBuilder row = result.newRow();
		row.add(Document.COLUMN_DOCUMENT_ID, documentId);
		fillStatRow(row, basename, stat.get());
		return result;
	}

	public Cursor queryRoots(String[] projection) {
		MatrixCursor result = new MatrixCursor(
				projection != null ? projection : DEFAULT_ROOT_PROJECTION);
		var sp = PreferenceManager.getDefaultSharedPreferences(getContext());
		String mountpoint = sp.getString("mountpoint", ".");
		if (mountpoint.equals("")) {
			mountpoint = ".";
		}
		var documentId = documentIdFromPath(mountpoint);
		var rootUri = getRootUri();
		MatrixCursor.RowBuilder row = result.newRow();
		for (var col : result.getColumnNames()) {
			// TODO make title, summary more useful
			row.add(switch (col) {
			case Root.COLUMN_ROOT_ID -> rootUri.toString();
			case Root.COLUMN_DOCUMENT_ID -> documentId;
			case Root.COLUMN_FLAGS -> 0;
			case Root.COLUMN_TITLE -> documentId;
			case Root.COLUMN_ICON -> R.mipmap.sym_def_app_icon;
			case Root.COLUMN_SUMMARY -> "SFTP with user: " + params.username();
			default -> null;
			});
		}
		return result;
	}
}
