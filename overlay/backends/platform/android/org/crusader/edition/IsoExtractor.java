package org.crusader.edition;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal ISO 9660 reader, just enough to copy the FLICS (cutscene) folder out
 * of the GAME.BIN disc image that the Steam and GOG releases ship.
 *
 * The Steam/GOG GAME.BIN files are plain 2048-byte-sector ISO images (DOSBox
 * mounts them with "imgmount -t iso"). Raw 2352-byte "MODE1/2352" images are
 * detected too, for people who ripped their own CD.
 */
public final class IsoExtractor {

	public interface Progress {
		/** @return false to cancel */
		boolean onProgress(String file, long done, long total);
	}

	public static final class Entry {
		public final String name;
		public final long lba;
		public final long size;
		public final boolean dir;

		Entry(String name, long lba, long size, boolean dir) {
			this.name = name;
			this.lba = lba;
			this.size = size;
			this.dir = dir;
		}
	}

	private final FileChannel ch;
	private int sectorSize = 2048;
	private int sectorHeader = 0;
	private Entry root;

	public IsoExtractor(FileChannel channel) throws IOException {
		ch = channel;
		if (!probe(2048, 0)) {
			if (!probe(2352, 16)) {
				throw new IOException("Not an ISO 9660 disc image");
			}
		}
	}

	private boolean probe(int ss, int hdr) throws IOException {
		sectorSize = ss;
		sectorHeader = hdr;
		byte[] pvd = readSector(16);
		if (pvd[0] != 1 || pvd[1] != 'C' || pvd[2] != 'D' || pvd[3] != '0' || pvd[4] != '0' || pvd[5] != '1') {
			return false;
		}
		// Root directory record lives at offset 156 of the PVD.
		root = parseRecord(pvd, 156);
		return root != null;
	}

	private byte[] readSector(long lba) throws IOException {
		byte[] out = new byte[2048];
		ByteBuffer bb = ByteBuffer.wrap(out);
		long pos = lba * sectorSize + sectorHeader;
		while (bb.hasRemaining()) {
			int r = ch.read(bb, pos + bb.position());
			if (r < 0) {
				throw new IOException("Unexpected end of disc image");
			}
		}
		return out;
	}

	private static long le32(byte[] b, int o) {
		return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
	}

	private static Entry parseRecord(byte[] b, int o) {
		int len = b[o] & 0xFF;
		if (len == 0) {
			return null;
		}
		long lba = le32(b, o + 2);
		long size = le32(b, o + 10);
		boolean dir = (b[o + 25] & 0x02) != 0;
		int nameLen = b[o + 32] & 0xFF;
		String name;
		if (nameLen == 1 && (b[o + 33] == 0 || b[o + 33] == 1)) {
			name = b[o + 33] == 0 ? "." : "..";
		} else {
			name = new String(b, o + 33, nameLen, StandardCharsets.ISO_8859_1);
			int semi = name.indexOf(';');
			if (semi >= 0) {
				name = name.substring(0, semi);
			}
			if (name.endsWith(".")) {
				name = name.substring(0, name.length() - 1);
			}
		}
		return new Entry(name, lba, size, dir);
	}

	public List<Entry> list(Entry dir) throws IOException {
		List<Entry> out = new ArrayList<>();
		long sectors = (dir.size + 2047) / 2048;
		for (long s = 0; s < sectors; s++) {
			byte[] sec = readSector(dir.lba + s);
			int o = 0;
			while (o < 2048) {
				int len = sec[o] & 0xFF;
				if (len == 0) {
					break; // rest of this sector is padding
				}
				Entry e = parseRecord(sec, o);
				if (e != null && !e.name.equals(".") && !e.name.equals("..")) {
					out.add(e);
				}
				o += len;
			}
		}
		return out;
	}

	public Entry find(String path) throws IOException {
		Entry cur = root;
		for (String part : path.split("/")) {
			if (part.isEmpty()) {
				continue;
			}
			Entry next = null;
			for (Entry e : list(cur)) {
				if (e.name.equalsIgnoreCase(part)) {
					next = e;
					break;
				}
			}
			if (next == null) {
				return null;
			}
			cur = next;
		}
		return cur;
	}

	/** Copy one directory (non-recursive, the FLICS folder is flat). */
	public int extractDir(String isoPath, File dest, Progress progress) throws IOException {
		Entry dir = find(isoPath);
		if (dir == null || !dir.dir) {
			throw new IOException("No " + isoPath + " folder on the disc image");
		}
		//noinspection ResultOfMethodCallIgnored
		dest.mkdirs();
		List<Entry> files = list(dir);
		long total = 0;
		for (Entry e : files) {
			if (!e.dir) {
				total += e.size;
			}
		}
		long done = 0;
		int count = 0;
		byte[] buf = new byte[2048 * 64];
		for (Entry e : files) {
			if (e.dir) {
				continue;
			}
			File out = new File(dest, e.name.toUpperCase(Locale.ROOT));
			File tmp = new File(dest, out.getName() + ".part");
			try (FileOutputStream fos = new FileOutputStream(tmp)) {
				long remaining = e.size;
				long lba = e.lba;
				while (remaining > 0) {
					if (sectorSize == 2048) {
						// Contiguous: read many sectors at once.
						int chunk = (int) Math.min(buf.length, remaining);
						ByteBuffer bb = ByteBuffer.wrap(buf, 0, chunk);
						long pos = lba * 2048L;
						while (bb.hasRemaining()) {
							int r = ch.read(bb, pos + bb.position());
							if (r < 0) {
								throw new IOException("Unexpected end of disc image");
							}
						}
						fos.write(buf, 0, chunk);
						lba += (chunk + 2047) / 2048;
						remaining -= chunk;
						done += chunk;
					} else {
						byte[] sec = readSector(lba++);
						int n = (int) Math.min(2048, remaining);
						fos.write(sec, 0, n);
						remaining -= n;
						done += n;
					}
					if (progress != null && !progress.onProgress(e.name, done, total)) {
						fos.close();
						//noinspection ResultOfMethodCallIgnored
						tmp.delete();
						throw new IOException("Cancelled");
					}
				}
			}
			if (out.exists()) {
				//noinspection ResultOfMethodCallIgnored
				out.delete();
			}
			if (!tmp.renameTo(out)) {
				throw new IOException("Could not write " + out);
			}
			count++;
		}
		return count;
	}
}
