package org.crusader.edition;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Copies a Crusader install that the player picked with the system folder
 * picker (Storage Access Framework) into the app's games/ folder.
 *
 * Only the files the engine needs are copied: STATIC, USECODE, SOUND and the
 * FLICS cutscenes. If the folder has no FLICS but has the Steam/GOG GAME.BIN
 * disc image, the cutscenes are read straight out of the image, so the
 * 500 MB image itself is never copied.
 */
public final class GameImporter {

	public interface Listener {
		void onStatus(String text, int percent);
		boolean isCancelled();
	}

	private static final String[] COPY_DIRS = { "STATIC", "USECODE", "SOUND", "FLICS" };
	private static final String[] COPY_FILES = { "CRUSADER.EXE", "REGRET.EXE", "CRUSADER.CFG" };

	static final class Doc {
		final String id;
		final String name;
		final boolean dir;
		final long size;

		Doc(String id, String name, boolean dir, long size) {
			this.id = id;
			this.name = name;
			this.dir = dir;
			this.size = size;
		}
	}

	private final Context ctx;
	private final ContentResolver cr;
	private final Uri tree;
	private final Listener listener;
	private long totalBytes;
	private long doneBytes;

	public GameImporter(Context ctx, Uri tree, Listener listener) {
		this.ctx = ctx;
		this.cr = ctx.getContentResolver();
		this.tree = tree;
		this.listener = listener;
	}

	private List<Doc> children(String parentId) {
		List<Doc> out = new ArrayList<>();
		Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
		String[] cols = {
			DocumentsContract.Document.COLUMN_DOCUMENT_ID,
			DocumentsContract.Document.COLUMN_DISPLAY_NAME,
			DocumentsContract.Document.COLUMN_MIME_TYPE,
			DocumentsContract.Document.COLUMN_SIZE,
		};
		try (Cursor c = cr.query(uri, cols, null, null, null)) {
			if (c == null) {
				return out;
			}
			while (c.moveToNext()) {
				boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
				out.add(new Doc(c.getString(0), c.getString(1), dir, c.isNull(3) ? 0 : c.getLong(3)));
			}
		}
		return out;
	}

	private static Doc pick(List<Doc> docs, String name) {
		for (Doc d : docs) {
			if (d.name != null && d.name.equalsIgnoreCase(name)) {
				return d;
			}
		}
		return null;
	}

	private static boolean looksLikeGame(List<Doc> docs) {
		Doc u = pick(docs, "USECODE");
		Doc s = pick(docs, "STATIC");
		return u != null && u.dir && s != null && s.dir;
	}

	/**
	 * Import every Crusader game found in the picked folder (the folder
	 * itself, or its direct sub-folders, so picking a folder holding both
	 * "Crusader No Remorse" and "Crusader No Regret" imports both).
	 *
	 * @return the games that were imported
	 */
	public List<GameLibrary.Game> run() throws IOException {
		String rootId = DocumentsContract.getTreeDocumentId(tree);
		List<Doc> top = children(rootId);
		List<List<Doc>> candidates = new ArrayList<>();
		List<String> names = new ArrayList<>();
		if (looksLikeGame(top)) {
			candidates.add(top);
			names.add("selected folder");
		} else {
			for (Doc d : top) {
				if (d.dir) {
					List<Doc> sub = children(d.id);
					if (looksLikeGame(sub)) {
						candidates.add(sub);
						names.add(d.name);
					}
				}
			}
		}
		if (candidates.isEmpty()) {
			throw new IOException("No Crusader game found here. Pick the folder that contains STATIC and USECODE (for example \"Crusader No Remorse\").");
		}

		List<GameLibrary.Game> imported = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			imported.add(importOne(candidates.get(i), names.get(i)));
		}
		return imported;
	}

	private GameLibrary.Game importOne(List<Doc> top, String label) throws IOException {
		File games = GameLibrary.gamesRoot(ctx);
		File staging = new File(games, ".importing");
		deleteRecursive(staging);
		//noinspection ResultOfMethodCallIgnored
		staging.mkdirs();

		// Work out the size up front for a meaningful progress bar.
		totalBytes = 0;
		doneBytes = 0;
		for (String d : COPY_DIRS) {
			Doc doc = pick(top, d);
			if (doc != null && doc.dir) {
				totalBytes += treeSize(doc);
			}
		}
		Doc disc = pick(top, "GAME.BIN");

		listener.onStatus("Copying " + label + "...", 0);
		for (String d : COPY_DIRS) {
			Doc doc = pick(top, d);
			if (doc != null && doc.dir) {
				copyTree(doc, new File(staging, d));
			}
		}
		for (String f : COPY_FILES) {
			Doc doc = pick(top, f);
			if (doc != null && !doc.dir) {
				copyFile(doc, new File(staging, f));
			}
		}

		if (disc != null && !disc.dir) {
			// Steam/GOG installs keep part of the game only on the disc image
			// (cutscenes, music, and for No Regret the level data). Pull in
			// whatever the folder lacks; the 500 MB image itself is not copied.
			listener.onStatus("Reading the rest of the game from GAME.BIN...", 0);
			Uri du = DocumentsContract.buildDocumentUriUsingTree(tree, disc.id);
			try (ParcelFileDescriptor pfd = cr.openFileDescriptor(du, "r")) {
				if (pfd == null) {
					throw new IOException("Cannot open GAME.BIN");
				}
				try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
					IsoExtractor iso = new IsoExtractor(fis.getChannel());
					iso.mergeMissing(staging, (file, done, total) -> {
						listener.onStatus("From GAME.BIN: " + file, (int) (100 * done / Math.max(1, total)));
						return !listener.isCancelled();
					});
				}
			}
			//noinspection ResultOfMethodCallIgnored
			new File(staging, GameLibrary.MERGED_MARKER).createNewFile();
		}

		GameLibrary.Game g = GameLibrary.identify(staging);
		if (g == null) {
			deleteRecursive(staging);
			throw new IOException("The files in " + label + " are not a Crusader game the engine supports.");
		}
		File dest = new File(games, g.kind.gameId);
		deleteRecursive(dest);
		if (!staging.renameTo(dest)) {
			throw new IOException("Could not move the imported game into place");
		}
		return GameLibrary.identify(dest);
	}

	private long treeSize(Doc dir) {
		long s = 0;
		for (Doc c : children(dir.id)) {
			s += c.dir ? treeSize(c) : c.size;
		}
		return s;
	}

	private void copyTree(Doc dir, File dest) throws IOException {
		//noinspection ResultOfMethodCallIgnored
		dest.mkdirs();
		for (Doc c : children(dir.id)) {
			if (listener.isCancelled()) {
				throw new IOException("Cancelled");
			}
			File out = new File(dest, c.name.toUpperCase(Locale.ROOT));
			if (c.dir) {
				copyTree(c, out);
			} else {
				copyFile(c, out);
			}
		}
	}

	private void copyFile(Doc doc, File out) throws IOException {
		Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, doc.id);
		byte[] buf = new byte[256 * 1024];
		try (InputStream in = cr.openInputStream(u); OutputStream os = new FileOutputStream(out)) {
			if (in == null) {
				throw new IOException("Cannot read " + doc.name);
			}
			int r;
			while ((r = in.read(buf)) > 0) {
				os.write(buf, 0, r);
				doneBytes += r;
				if (totalBytes > 0) {
					listener.onStatus("Copying " + doc.name, (int) (100 * doneBytes / totalBytes));
				}
				if (listener.isCancelled()) {
					throw new IOException("Cancelled");
				}
			}
		}
	}

	static void deleteRecursive(File f) {
		if (f == null || !f.exists()) {
			return;
		}
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteRecursive(k);
			}
		}
		//noinspection ResultOfMethodCallIgnored
		f.delete();
	}
}
