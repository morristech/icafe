/**
 * Copyright (c) 2014 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 *
 * Change History - most recent changes go on top of previous changes
 *
 * TIFFWriter.java
 *
 * Who   Date       Description
 * ====  =========  =================================================================
 * WY    08May2014  Added insertExif() to insert EXIF data to TIFF page
 * WY    26Apr2014  Rewrite insertPage() to insert multiple pages one at a time
 * WY    11Apr2014  Added writeMultipageTIFF() to support creating multiple page TIFFs
 * WY    09Apr2014  Added splitPages() to split multiple page TIFFs into single page TIFFs
 * WY    09Apr2014  Added insertPages() to insert pages to multiple page TIFFs
 * WY    08Apr2014  Added insertPage() to insert a single page to multiple page TIFFs
 * WY    07Apr2014  Added getPageCount() to get the total pages for a TIFF image
 * WY    06Apr2014  Added retainPages() to keep pages from multiple page TIFFs
 * WY    04Apr2014  Added removePages() to remove pages from multiple page TIFFs
 * WY    02Apr2014  Added writePageData() for multiple page TIFFs
 */

package cafe.image.tiff;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import cafe.image.core.ImageMeta;
import cafe.image.jpeg.Marker;
import cafe.image.tiff.TiffFieldEnum.*;
import cafe.image.meta.exif.Exif;
import cafe.image.meta.exif.ExifTag;
import cafe.image.meta.exif.GPSTag;
import cafe.image.meta.exif.InteropTag;
import cafe.image.util.IMGUtils;
import cafe.image.writer.TIFFWriter;
import cafe.io.FileCacheRandomAccessOutputStream;
import cafe.io.IOUtils;
import cafe.io.RandomAccessInputStream;
import cafe.io.RandomAccessOutputStream;
import cafe.io.ReadStrategyII;
import cafe.io.ReadStrategyMM;
import cafe.io.WriteStrategyII;
import cafe.io.WriteStrategyMM;
import cafe.string.StringUtils;
import static cafe.image.writer.TIFFWriter.*;

/**
 * TIFF image tweaking tool
 * 
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 03/28/2014
 */
public class TIFFTweaker {
	/**
	 * Read an input TIFF and write it to a new TIFF.
	 * The EXIF and GPS information, if present, are preserved.
	 * 
	 * @param rin a RandomAccessInputStream 
	 * @param rout a RandomAccessOutputStream
	 * @throws IOException
	 */
	public static void copyCat(RandomAccessInputStream rin, RandomAccessOutputStream rout) throws IOException {
		List<IFD> list = new ArrayList<IFD>();
	   
		int offset = copyHeader(rin, rout);
		
		int writeOffset = FIRST_WRITE_OFFSET;
		// Read the IFDs into a list first
		readIFDs(null, null, TiffTag.class, list, offset, rin);
		offset = copyPages(list, writeOffset, rin, rout);
		int firstIFDOffset = list.get(0).getStartOffset();	

		writeToStream(rout, firstIFDOffset);
	}
	
	private static int copyHeader(RandomAccessInputStream rin, RandomAccessOutputStream rout) throws IOException {		
		rin.seek(STREAM_HEAD);
		// First 2 bytes determine the byte order of the file, "MM" or "II"
	    short endian = rin.readShort();
	
		if (endian == IOUtils.BIG_ENDIAN) {
		    System.out.println("Byte order: Motorola BIG_ENDIAN");
		    rin.setReadStrategy(ReadStrategyMM.getInstance());
		    rout.setWriteStrategy(WriteStrategyMM.getInstance());
		} else if(endian == IOUtils.LITTLE_ENDIAN) {
		    System.out.println("Byte order: Intel LITTLE_ENDIAN");
		    rin.setReadStrategy(ReadStrategyII.getInstance());
		    rout.setWriteStrategy(WriteStrategyII.getInstance());
		} else {
			rin.close();
			rout.close();
			throw new RuntimeException("Invalid TIFF byte order");
	    } 
		
		rout.writeShort(endian);
		// Read TIFF identifier
		rin.seek(0x02);
		short tiff_id = rin.readShort();
		
		if(tiff_id!=0x2a)//"*" 42 decimal
		{
		   rin.close();
		   rout.close();
		   throw new RuntimeException("Invalid TIFF identifier");
		}
		
		rout.writeShort(tiff_id);
		rin.seek(OFFSET_TO_WRITE_FIRST_IFD_OFFSET);
		
		return rin.readInt();
	}
	
	private static TiffField<?> copyJPEGHufTable(RandomAccessInputStream rin, RandomAccessOutputStream rout, TiffField<?> field, int curPos) throws IOException
	{
		int[] data = field.getDataAsLong();
		int[] tmp = new int[data.length];
	
		for(int i = 0; i < data.length; i++) {
			rin.seek(data[i]);
			tmp[i] = curPos;
			byte[] htable = new byte[16];
			IOUtils.readFully(rin, htable);
			IOUtils.write(rout, htable);			
			curPos += 16;
			
			int numCodes = 0;
			
            for(int j = 0; j < 16; j++) {
                numCodes += htable[j]&0xff;
            }
            
            curPos += numCodes;
            
            htable = new byte[numCodes];
            IOUtils.readFully(rin, htable);
			IOUtils.write(rout, htable);
		}
		
		if(TiffTag.fromShort(field.getTag()) == TiffTag.JPEG_AC_TABLES)
			return new LongField(TiffTag.JPEG_AC_TABLES.getValue(), tmp);
	
		return new LongField(TiffTag.JPEG_DC_TABLES.getValue(), tmp);
	}
	
	private static void copyJPEGInfoOffset(RandomAccessInputStream rin, RandomAccessOutputStream rout, int offset, int outOffset) throws IOException 
	{		
		boolean finished = false;
		int length = 0;	
		short marker;
		Marker emarker;
		
		rin.seek(offset);
		rout.seek(outOffset);
		// The very first marker should be the start_of_image marker!	
		if(Marker.fromShort(IOUtils.readShortMM(rin)) != Marker.SOI)
		{
			System.out.println("Invalid JPEG image, expected SOI marker not found!");
			return;
		}
		
		System.out.println(Marker.SOI);
		IOUtils.writeShortMM(rout, Marker.SOI.getValue());
		
		marker = IOUtils.readShortMM(rin);
			
		while (!finished)
	    {	        
			if (Marker.fromShort(marker) == Marker.EOI)
			{
				System.out.println(Marker.EOI);
				IOUtils.writeShortMM(rout, marker);
				finished = true;
			}
		   	else // Read markers
			{
		   		emarker = Marker.fromShort(marker);
				System.out.println(emarker); 
				
				switch (emarker) {
					case JPG: // JPG and JPGn shouldn't appear in the image.
					case JPG0:
					case JPG13:
				    case TEM: // The only stand alone mark besides SOI, EOI, and RSTn. 
				    	marker = IOUtils.readShortMM(rin);
				    	break;
				    case SOS:						
						marker = copyJPEGSOS(rin, rout);
						break;
				    case PADDING:	
				    	int nextByte = 0;
				    	while((nextByte = rin.read()) == 0xff) {;}
				    	marker = (short)((0xff<<8)|nextByte);
				    	break;
				    default:
					    length = IOUtils.readUnsignedShortMM(rin);
					    byte[] buf = new byte[length - 2];
					    rin.read(buf);
					    IOUtils.writeShortMM(rout, marker);
					    IOUtils.writeShortMM(rout, length);
					    rout.write(buf);
					    marker = IOUtils.readShortMM(rin);					 
				}
			}
	    }
	}
	
	private static TiffField<?> copyJPEGQTable(RandomAccessInputStream rin, RandomAccessOutputStream rout, TiffField<?> field, int curPos) throws IOException
	{
		byte[] qtable = new byte[64];
		int[] data = field.getDataAsLong();
		int[] tmp = new int[data.length];
		
		for(int i = 0; i < data.length; i++) {
			rin.seek(data[i]);
			tmp[i] = curPos;
			IOUtils.readFully(rin, qtable);
			IOUtils.write(rout, qtable);
			curPos += 64;
		}
		
		return new LongField(TiffTag.JPEG_Q_TABLES.getValue(), tmp);
	}
	
	private static short copyJPEGSOS(RandomAccessInputStream rin, RandomAccessOutputStream rout) throws IOException 
	{
		int len = IOUtils.readUnsignedShortMM(rin);
		byte buf[] = new byte[len - 2];
		IOUtils.readFully(rin, buf);
		IOUtils.writeShortMM(rout, Marker.SOS.getValue());
		IOUtils.writeShortMM(rout, len);
		rout.write(buf);		
		// Actual image data follow.
		int nextByte = 0;
		short marker = 0;	
		
		while((nextByte = IOUtils.read(rin)) != -1)
		{
			rout.write(nextByte);
			
			if(nextByte == 0xff)
			{
				nextByte = IOUtils.read(rin);
			    rout.write(nextByte);
			    
				if (nextByte == -1) {
					throw new IOException("Premature end of SOS segment!");					
				}								
				
				if (nextByte != 0x00)
				{
					marker = (short)((0xff<<8)|nextByte);
					
					switch (Marker.fromShort(marker)) {										
						case RST0:  
						case RST1:
						case RST2:
						case RST3:
						case RST4:
						case RST5:
						case RST6:
						case RST7:
							System.out.println(Marker.fromShort(marker));
							continue;
						default:
					}
					break;
				}
			}
		}
		
		if (nextByte == -1) {
			throw new IOException("Premature end of SOS segment!");
		}

		return marker;
	}
	
	/**
	 * @param offset offset to write page image data
	 * 
	 * @return the position where to write the IFD for the current image page
	 */
	private static int copyPageData(IFD ifd, int offset, RandomAccessInputStream rin, RandomAccessOutputStream rout) throws IOException {
		// Original image data start from these offsets.
		TiffField<?> stripOffSets = ifd.removeField(TiffTag.STRIP_OFFSETS.getValue());
		
		if(stripOffSets == null)
			stripOffSets = ifd.removeField(TiffTag.TILE_OFFSETS.getValue());
				
		TiffField<?> stripByteCounts = ifd.getField(TiffTag.STRIP_BYTE_COUNTS.getValue());
		
		if(stripByteCounts == null)
			stripByteCounts = ifd.getField(TiffTag.TILE_BYTE_COUNTS.getValue());		
		/* 
		 * Make sure this will work in the case when neither STRIP_OFFSETS nor TILE_OFFSETS presents.
		 * Not sure if this will ever happen for TIFF. JPEG EXIF data do not contain these fields. 
		 */
		if(stripOffSets != null) { 
			int[] counts = stripByteCounts.getDataAsLong();		
			int[] off = stripOffSets.getDataAsLong();
			int[] temp = new int[off.length];
			
			// We are going to write the image data first
			rout.seek(offset);
			
			TiffField<?> tiffField = ifd.getField(TiffTag.COMPRESSION.getValue());
			
			if(tiffField != null && tiffField.getDataAsLong()[0] != 1) {// Compressed TIFF		
				for(int i = 0; i < off.length; i++) {
					rin.seek(off[i]);
					byte[] buf = new byte[counts[i]];
					rin.read(buf);
					rout.write(buf);
					temp[i] = offset;
					offset += buf.length;
				}
			} else {// Uncompressed TIFF.
				// Bug fix for uncompressed image with wrong StripByteCounts value
				tiffField = ifd.getField(TiffTag.IMAGE_WIDTH.getValue());
				int imageWidth = tiffField.getDataAsLong()[0];
				tiffField = ifd.getField(TiffTag.IMAGE_LENGTH.getValue());
				int imageHeight = tiffField.getDataAsLong()[0];
				
				int samplesPerPixel = 1;
				tiffField = ifd.getField(TiffTag.SAMPLES_PER_PIXEL.getValue());
				
				if(tiffField != null) {
					samplesPerPixel = tiffField.getDataAsLong()[0];
				}
				
				int[] bitsPerSample = new int[samplesPerPixel];
				Arrays.fill(bitsPerSample, 1);
				tiffField = ifd.getField(TiffTag.BITS_PER_SAMPLE.getValue());
								
				if(tiffField != null) {
					bitsPerSample = tiffField.getDataAsLong();
				}
				
				int rowsPerStrip = imageHeight;
				tiffField = ifd.getField(TiffTag.ROWS_PER_STRIP.getValue());
			
				if(tiffField != null) {
					rowsPerStrip = tiffField.getDataAsLong()[0];
				}
				
				int planarConfiguration = PlanarConfiguration.CONTIGUOUS.getValue();
				tiffField = ifd.getField(TiffTag.PLANAR_CONFIGURATTION.getValue());
				
				if(tiffField != null) {
					planarConfiguration = tiffField.getDataAsLong()[0];
				}							
				
				if(planarConfiguration == PlanarConfiguration.CONTIGUOUS.getValue()) { // Contiguous
					int bitsPerPixel = 0;
					int bytesPerStrip = 0;
					for(int i = 0; i < samplesPerPixel; i++) {
						bitsPerPixel += bitsPerSample[i];
					}
					int bitsPerStrip = bitsPerPixel * rowsPerStrip * imageWidth;					
					bytesPerStrip = bitsPerStrip / 8;
					if(bitsPerStrip % 8 != 0) bytesPerStrip++;
					
					int totalBits = bitsPerPixel * imageWidth * imageHeight;
					int totalBytes = totalBits / 8;
					if(totalBits % 8 != 0) totalBytes++;
					
					int bytesRead = 0;					
					for(int i = 0; i < off.length - 1; i++) {
						rin.seek(off[i]);
						byte[] buf = new byte[bytesPerStrip];
						bytesRead += bytesPerStrip;
						rin.read(buf);			
						rout.write(buf);
						temp[i] = offset;
						offset += buf.length;
					}					
					rin.seek(off[off.length - 1]);
					byte[] buf = new byte[totalBytes - bytesRead];
					rin.read(buf);			
					rout.write(buf);
					temp[off.length - 1] = offset;
					offset += buf.length;
				} else { // Separate planes
					int[] bitsPerStrip = new int[samplesPerPixel];
					int[] bytesPerStrip = new int[samplesPerPixel];
					for(int i = 0; i < samplesPerPixel; i++) {
						bitsPerStrip[i] = bitsPerSample[i] * imageWidth * rowsPerStrip;
						bytesPerStrip[i] = bitsPerStrip[i] / 8;
						if(bitsPerStrip[i] % 8 != 0) bytesPerStrip[i]++;
					}
					int[] totalBits = new int[samplesPerPixel];
					int[] totalBytes = new int[samplesPerPixel];
					for(int i = 0; i < samplesPerPixel; i++) {
						totalBits[i] = imageWidth * imageHeight * bitsPerSample[i];
						totalBytes[i] = totalBits[i] / 8;
						if(totalBits[i] % 8 != 0 ) totalBytes[i]++;
					}
					
					int[] bytesRead = new int[samplesPerPixel];
					
					for(int i = 0; i < off.length - samplesPerPixel;) {
						for(int j = 0; j < samplesPerPixel; j++) {
							rin.seek(off[i]);
							byte[] buf = new byte[bytesPerStrip[j]];
							bytesRead[j] += bytesPerStrip[j];
							rin.read(buf);			
							rout.write(buf);
							temp[i++] = offset;
							offset += buf.length;
						}						
					}
					
					for(int j = 0; j < samplesPerPixel; j++) {
						rin.seek(off[off.length - samplesPerPixel + j ]);
						byte[] buf = new byte[totalBytes[j] - bytesRead[j]];
						rin.read(buf);			
						rout.write(buf);
						temp[off.length - samplesPerPixel + j] = offset;
						offset += buf.length;
					}
				}				
			}
			// End of bug fix for uncompressed TIFF image
			if(ifd.getField(TiffTag.STRIP_BYTE_COUNTS.getValue()) != null)
				stripOffSets = new LongField(TiffTag.STRIP_OFFSETS.getValue(), temp);
			else
				stripOffSets = new LongField(TiffTag.TILE_OFFSETS.getValue(), temp);		
			ifd.addField(stripOffSets);		
		}
		// add copyright and software fields.
		String copyRight = "Copyright (c) Wen Yu, 2014 (yuwen_66@yahoo.com)";
		ifd.addField(new ASCIIField(TiffTag.COPYRIGHT.getValue(), copyRight));
		
		String softWare = "TIFFTweaker 1.0";
		ifd.addField(new ASCIIField(TiffTag.SOFTWARE.getValue(), softWare));
		// End of copyright and software field.
		
		/* The following are added to work with old-style JPEG compression (type 6) */
		/* One of the flavors (found in JPEG EXIF thumbnail IFD - IFD1) of the old JPEG compression contains this field */
		TiffField<?> jpegInfoOffset = ifd.removeField(TiffTag.JPEG_INTERCHANGE_FORMAT.getValue());
		if(jpegInfoOffset != null) {
			copyJPEGInfoOffset(rin, rout, jpegInfoOffset.getDataAsLong()[0], offset);
			jpegInfoOffset = new LongField(TiffTag.JPEG_INTERCHANGE_FORMAT.getValue(), new int[]{offset});
			ifd.addField(jpegInfoOffset);
		}
		/* Another flavor of the old style JPEG compression type 6 contains separate tables */
		TiffField<?> jpegTable = ifd.removeField(TiffTag.JPEG_DC_TABLES.getValue());
		if(jpegTable != null) {
			ifd.addField(copyJPEGHufTable(rin, rout, jpegTable, (int)rout.getStreamPointer()));
		}
		
		jpegTable = ifd.removeField(TiffTag.JPEG_AC_TABLES.getValue());
		if(jpegTable != null) {
			ifd.addField(copyJPEGHufTable(rin, rout, jpegTable, (int)rout.getStreamPointer()));
		}
	
		jpegTable = ifd.removeField(TiffTag.JPEG_Q_TABLES.getValue());
		if(jpegTable != null) {
			ifd.addField(copyJPEGQTable(rin, rout, jpegTable, (int)rout.getStreamPointer()));
		}
		/* End of code to work with old-style JPEG compression */
		// Return the actual stream position (we may have lost track of it)  
		return (int)rout.getStreamPointer();	
	}
	
	// Copy a list of IFD and associated image data if any
	private static int copyPages(List<IFD> list, int writeOffset, RandomAccessInputStream rin, RandomAccessOutputStream rout) throws IOException {
		// Write the first page data
		writeOffset = copyPageData(list.get(0), writeOffset, rin, rout);
		// Then write the first IFD
		writeOffset = list.get(0).write(rout, writeOffset);
		// We are going to write the remaining image pages and IFDs if any
		for(int i = 1; i < list.size(); i++) {
			writeOffset = copyPageData(list.get(i), writeOffset, rin, rout);
			// Tell the IFD to update next IFD offset for the following IFD
			list.get(i-1).setNextIFDOffset(rout, writeOffset); 
			writeOffset = list.get(i).write(rout, writeOffset);
		}
		
		return writeOffset;
	}
	
	public static void finishInsert(RandomAccessOutputStream rout, List<IFD> list) throws IOException {
		// Reset pageNumber and total pages
		for(int i = 0; i < list.size(); i++) {
			int offset = list.get(i).getField(TiffTag.PAGE_NUMBER.getValue()).getDataOffset();
			rout.seek(offset);
			rout.writeShort((short)i); // Update page number for this page
			rout.writeShort((short)list.size()); // Update total page number
		}		
		// Link the IFDs
		for(int i = 0; i < list.size() - 1; i++)
			list.get(i).setNextIFDOffset(rout, list.get(i+1).getStartOffset());
				
		int firstIFDOffset = list.get(0).getStartOffset();

		writeToStream(rout, firstIFDOffset);
	}
	
	public static int getPageCount(RandomAccessInputStream rin) throws IOException {
		List<IFD> list = new ArrayList<IFD>();
		readIFDs(list, rin);
			
		return list.size();
	}
	
	/**
	 * Insert EXIF data with optional thumbnail IFD
	 * 
	 * @param rin input image stream
	 * @param rout output image stream
	 * @param exif EXIF wrapper instance
	 * @throws Exception
	 */
	public static void insertExif(RandomAccessInputStream rin, RandomAccessOutputStream rout, Exif exif) throws Exception {
		// If no thumbnail image is provided in EXIF wrapper, one will be created from the input stream
		if(exif.isThumbnailRequired() && !exif.hasThumbnail()) {
			BufferedImage original = javax.imageio.ImageIO.read(rin);
			int imageWidth = original.getWidth();
			int imageHeight = original.getHeight();
			int thumbnailWidth = 160;
			int thumbnailHeight = 120;
			if(imageWidth < imageHeight) { // Swap thumbnail width and height to keep a relative aspect ratio
				int temp = thumbnailWidth;
				thumbnailWidth = thumbnailHeight;
				thumbnailHeight = temp;
			}			
			if(imageWidth < thumbnailWidth) thumbnailWidth = imageWidth;			
			if(imageHeight < thumbnailHeight) thumbnailHeight = imageHeight;
			BufferedImage thumbnail = new BufferedImage(thumbnailWidth, thumbnailHeight, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = thumbnail.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
			        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(original, 0, 0, thumbnailWidth, thumbnailHeight, null);
			// Insert the thumbnail into EXIF wrapper
			exif.setThumbnail(thumbnail);
			// Reset the stream pointer
			rin.seek(0);
		}
		int offset = copyHeader(rin, rout);
		// Read the IFDs into a list first
		List<IFD> ifds = new ArrayList<IFD>();
		readIFDs(null, null, TiffTag.class, ifds, offset, rin);
		IFD imageIFD = ifds.get(0);
		if(exif.getIFD(TiffTag.EXIF_SUB_IFD) != null) {
			imageIFD.addField(new LongField(TiffTag.EXIF_SUB_IFD.getValue(), new int[]{0})); // Place holder
			imageIFD.addChild(TiffTag.EXIF_SUB_IFD, exif.getIFD(TiffTag.EXIF_SUB_IFD));
		}
		if(exif.getIFD(TiffTag.GPS_SUB_IFD) != null) {
			imageIFD.addField(new LongField(TiffTag.GPS_SUB_IFD.getValue(), new int[]{0})); // Place holder
			imageIFD.addChild(TiffTag.GPS_SUB_IFD, exif.getIFD(TiffTag.GPS_SUB_IFD));
		}
		int writeOffset = FIRST_WRITE_OFFSET;
		// Copy pages
		writeOffset = copyPages(ifds.subList(0, 1), writeOffset, rin, rout);
		if(exif.isThumbnailRequired() && exif.hasThumbnail())
			imageIFD.setNextIFDOffset(rout, writeOffset);
		// This line is very important!!!
		rout.seek(writeOffset);
		exif.write(rout);
		int firstIFDOffset = imageIFD.getStartOffset();

		writeToStream(rout, firstIFDOffset);
	}
	
	/**
	 * Insert a single page into a TIFF image
	 * 
	 * @param image a BufferedImage to insert
	 * @param index index (relative to the existing pages) to insert the page
	 * @param rout RandomAccessOutputStream to write new image
	 * @param ifds a list of IFDs
	 * @param writeOffset stream offset to insert this page
	 * @param writer TIFFWriter instance
	 * @throws IOException
	 * 
	 * @return stream offset after inserting this page
	 */
	public static int insertPage(BufferedImage image, int index, RandomAccessOutputStream rout, List<IFD> ifds, int writeOffset, TIFFWriter writer) throws IOException {
		// Sanity check
		if(index < 0) index = 0;
		else if(index > ifds.size()) index = ifds.size();		
		
		// Grab image pixels in ARGB format
		int imageWidth = image.getWidth();
		int imageHeight = image.getHeight();
		int[] pixels = IMGUtils.getRGB(image);//image.getRGB(0, 0, imageWidth, imageHeight, null, 0, imageWidth);
		
		try {
			writeOffset = writer.writePage(pixels, index, ifds.size(), imageWidth, imageHeight, rout, writeOffset);
			ifds.add(index, writer.getIFD());
		} catch (Exception e) {
			e.printStackTrace();
		}	
		
		return writeOffset;
	}
	
	public static void insertPages(RandomAccessInputStream rin, RandomAccessOutputStream rout, TIFFWriter writer, int pageNumber, BufferedImage... images) throws IOException {
		insertPages(rin, rout, writer, pageNumber, null, images);
	}
	
	/**
	 * Insert pages into a TIFF image
	 * 
	 * @param images a number of BufferedImage to insert
	 * @param pageNumber first page number
	 * @param rin RandomAccessInputStream to read old image
	 * @param rout RandomAccessOutputStream to write new image
	 * @param writer TIFFWriter instance
	 * @throws IOException
	 */
	public static void insertPages(RandomAccessInputStream rin, RandomAccessOutputStream rout, TIFFWriter writer, int pageNumber, ImageMeta[] imageMeta, BufferedImage... images) throws IOException {
		int offset = copyHeader(rin, rout);
		
		List<IFD> list = new ArrayList<IFD>();
		List<IFD> insertedList = new ArrayList<IFD>(images.length);
		
		// Read the IFDs into a list first
		readIFDs(null, null, TiffTag.class, list, offset, rin);
		
		if(pageNumber < 0) pageNumber = 0;
		else if(pageNumber > list.size()) pageNumber = list.size();
		
		int minPageNumber = pageNumber;
		
		int maxPageNumber = list.size() + images.length;
		
		int writeOffset = FIRST_WRITE_OFFSET;
		
		ImageMeta[] meta = null;
		
		if(imageMeta == null) {
			meta = new ImageMeta[images.length];
			Arrays.fill(meta, writer.getImageMeta());
		} else if(images.length > imageMeta.length && imageMeta.length > 0) {
				meta = new ImageMeta[images.length];
				System.arraycopy(imageMeta, 0, meta, 0, imageMeta.length);
				Arrays.fill(meta, imageMeta.length, images.length, imageMeta[imageMeta.length - 1]);
		} else {
			meta = imageMeta;
		}
	
		for(int i = 0; i < images.length; i++) {
			// Retrieve image dimension
			int imageWidth = images[i].getWidth();
			int imageHeight = images[i].getHeight();
			// Grab image pixels in ARGB format and write image
			int[] pixels = IMGUtils.getRGB(images[i]);//images[i].getRGB(0, 0, imageWidth, imageHeight, null, 0, imageWidth);
			
			try {
				writer.setImageMeta(meta[i]);
				writeOffset = writer.writePage(pixels, pageNumber++, maxPageNumber, imageWidth, imageHeight, rout, writeOffset);
				insertedList.add(writer.getIFD());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		// Reset pageNumber for the existing pages
		for(int i = 0; i < minPageNumber; i++) {
			list.get(i).removeField(TiffTag.PAGE_NUMBER.getValue());
			list.get(i).addField(new ShortField(TiffTag.PAGE_NUMBER.getValue(), new short[]{(short)i, (short)maxPageNumber}));
		}
		
		for(int i = minPageNumber; i < list.size(); i++) {
			list.get(i).removeField(TiffTag.PAGE_NUMBER.getValue());
			list.get(i).addField(new ShortField(TiffTag.PAGE_NUMBER.getValue(), new short[]{(short)(i + images.length), (short)maxPageNumber}));
		}
		
		if(list.size() == 1) { // Make the original image one page of the new multiple page TIFF
			if(list.get(0).removeField(TiffTag.SUBFILE_TYPE.getValue()) == null)
				list.get(0).removeField(TiffTag.NEW_SUBFILE_TYPE.getValue());
			list.get(0).addField(new ShortField(TiffTag.SUBFILE_TYPE.getValue(), new short[]{3}));
		}
		
		// Copy pages
		writeOffset = copyPages(list, writeOffset, rin, rout);
		// Re-link the IFDs
		// Internally link inserted IFDs first
		for(int i = 0; i < images.length - 1; i++) {
			insertedList.get(i).setNextIFDOffset(rout, insertedList.get(i+1).getStartOffset());
		}
		// Link first inserted image IFD with the old previous one
		if(minPageNumber != 0) // Not added at the head
			list.get(minPageNumber - 1).setNextIFDOffset(rout, insertedList.get(0).getStartOffset());
		if(minPageNumber != list.size()) // Link the last inserted image with the old next one
			insertedList.get(insertedList.size() - 1).setNextIFDOffset(rout, list.get(minPageNumber).getStartOffset());
		
		int firstIFDOffset = 0;
			
		if(minPageNumber == 0) {
			firstIFDOffset = insertedList.get(0).getStartOffset();			
		} else {
			firstIFDOffset = list.get(0).getStartOffset();
		}
		
		writeToStream(rout, firstIFDOffset);
	}
	
	public static int prepareForInsert(RandomAccessInputStream rin, RandomAccessOutputStream rout, List<IFD> ifds) throws IOException {
		int offset = copyHeader(rin, rout);
		// Read the IFDs into a list first
		readIFDs(null, null, TiffTag.class, ifds, offset, rin);
		if(ifds.size() == 1) { // Make the original image one page of the new multiple page TIFF
			if(ifds.get(0).removeField(TiffTag.SUBFILE_TYPE.getValue()) == null)
				ifds.get(0).removeField(TiffTag.NEW_SUBFILE_TYPE.getValue());
			ifds.get(0).addField(new ShortField(TiffTag.SUBFILE_TYPE.getValue(), new short[]{3}));
		}		
		for(int i = 0; i < ifds.size(); i++) {
			ifds.get(i).removeField(TiffTag.PAGE_NUMBER.getValue());
			// Place holder, to be updated later
			ifds.get(i).addField(new ShortField(TiffTag.PAGE_NUMBER.getValue(), new short[]{0, 0}));
		}
		int writeOffset = FIRST_WRITE_OFFSET;
		// Copy pages
		writeOffset = copyPages(ifds, writeOffset, rin, rout);
		
		return writeOffset;
	}
	
	private static int readHeader(RandomAccessInputStream rin) throws IOException {
		int offset = 0;
	    // First 2 bytes determine the byte order of the file
		rin.seek(STREAM_HEAD);
	    short endian = rin.readShort();
	    offset += 2;
	
		if (endian == IOUtils.BIG_ENDIAN)
		{
		    System.out.println("Byte order: Motorola BIG_ENDIAN");
		    rin.setReadStrategy(ReadStrategyMM.getInstance());
		}
		else if(endian == IOUtils.LITTLE_ENDIAN)
		{
		    System.out.println("Byte order: Intel LITTLE_ENDIAN");
		    rin.setReadStrategy(ReadStrategyII.getInstance());
		}
		else {		
			rin.close();
			throw new RuntimeException("Invalid TIFF byte order");
	    }
		
		// Read TIFF identifier
		rin.seek(offset);
		short tiff_id = rin.readShort();
		offset +=2;
		if(tiff_id!=0x2a)//"*" 42 decimal
		{
			rin.close();
			throw new RuntimeException("Invalid TIFF identifier");
		}
		rin.seek(offset);
		offset = rin.readInt();
			
		return offset;
	}
	
	private static int readIFD(IFD parent, Tag parentTag, Class<?> tagClass, RandomAccessInputStream rin, List<IFD> list, int offset, String indent) throws IOException 
	{	
		// Use reflection to invoke fromShort(short) method
		Method method = null;
		try {
			method = tagClass.getDeclaredMethod("fromShort", short.class);
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		}
		String indent2 = indent + "-----"; // Increment indentation
		IFD tiffIFD = new IFD();
		rin.seek(offset);
		int no_of_fields = rin.readShort();
		System.out.print(indent);
		System.out.println("Total number of fields: " + no_of_fields);
		offset += 2;
		
		for (int i = 0; i < no_of_fields; i++)
		{
			System.out.print(indent);
			System.out.println("Field "+i+" =>");
			rin.seek(offset);
			short tag = rin.readShort();
			Tag ftag = null;
			try {
				ftag = (Tag)method.invoke(null, tag);
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			}
			System.out.print(indent);
			if (ftag == TiffTag.UNKNOWN) {
				System.out.println("Tag: " + ftag + " [Value: 0x"+ Integer.toHexString(tag&0xffff) + "]" + " (Unknown)");
			} else {
				System.out.println("Tag: " + ftag);
			}
			offset += 2;
			rin.seek(offset);
			short type = rin.readShort();
			FieldType ftype = FieldType.fromShort(type);
			System.out.print(indent);
			System.out.println("Data type: " + ftype);
			offset += 2;
			rin.seek(offset);
			int field_length = rin.readInt();
			System.out.print(indent);
			System.out.println("Field length: " + field_length);
			offset += 4;
			////// Try to read actual data.
			switch (ftype)
			{
				case BYTE:
				case UNDEFINED:
					byte[] data = new byte[field_length];
					if(field_length <= 4) {
						rin.seek(offset);
						rin.readFully(data, 0, field_length);					   
					}
					else {
						rin.seek(offset);
						rin.seek(rin.readInt());
						rin.readFully(data, 0, field_length);
					}
					System.out.print(indent);
					if(ftag == ExifTag.EXIF_VERSION || ftag == ExifTag.FLASH_PIX_VERSION)
						System.out.println("Field value: " + new String(data));
					else
						System.out.println("Field value: " + StringUtils.byteArrayToHexString(data));
					offset += 4;					
					tiffIFD.addField((ftype == FieldType.BYTE)?new ByteField(tag, data):new UndefinedField(tag, data));
					break;
				case ASCII:
					data = new byte[field_length];
					if(field_length <= 4) {
						rin.seek(offset);
						rin.readFully(data, 0, field_length);
					}						
					else {
						rin.seek(offset);
						rin.seek(rin.readInt());
						rin.readFully(data, 0, field_length);
					}
					if(data.length>0) {
						System.out.print(indent);
						System.out.println("Field value: " + new String(data, 0, data.length).trim());
					}
					offset += 4;	
					tiffIFD.addField(new ASCIIField(tag, new String(data, 0, data.length)));
			        break;
				case SHORT:
					short[] sdata = new short[field_length];
					if(field_length == 1) {
					  rin.seek(offset);
					  sdata[0] = rin.readShort();
					  offset += 4;
					}
					else if (field_length == 2)
					{
						rin.seek(offset);
						sdata[0] = rin.readShort();
						offset += 2;
						rin.seek(offset);
						sdata[1] = rin.readShort();
						offset += 2;
					}
					else {
						rin.seek(offset);
						int toOffset = rin.readInt();
						offset += 4;
						for (int j = 0; j  <field_length; j++){
							rin.seek(toOffset);
							sdata[j] = rin.readShort();
							toOffset += 2;
						}
					}	
					tiffIFD.addField(new ShortField(tag, sdata));
					System.out.print(indent);
					System.out.println("Field value: " + StringUtils.shortArrayToString(sdata, true) + " " + ftag.getFieldDescription(sdata[0]&0xffff));
					break;
				case LONG:
					int[] ldata = new int[field_length];
					if(field_length == 1) {
					  rin.seek(offset);
					  ldata[0] = rin.readInt();
					  offset += 4;
					}
					else {
						rin.seek(offset);
						int toOffset = rin.readInt();
						offset += 4;
						for (int j=0;j<field_length; j++){
							rin.seek(toOffset);
							ldata[j] = rin.readInt();
							toOffset += 4;
						}
					}
					System.out.print(indent);
					System.out.println("Field value: " + StringUtils.longArrayToString(ldata, true) + " " + ftag.getFieldDescription(ldata[0]&0xffff));
					if ((ftag == TiffTag.EXIF_SUB_IFD) && (ldata[0]!= 0)) {
						System.out.print(indent);
						System.out.println("<<ExifSubIFD: offset byte " + offset + ">>");
						readIFD(tiffIFD, TiffTag.EXIF_SUB_IFD, ExifTag.class, rin, list, ldata[0], indent2);						
					} else if ((ftag == TiffTag.GPS_SUB_IFD) && (ldata[0] != 0)) {
						System.out.print(indent);
						System.out.println("<<GPSSubIFD: offset byte " + offset + ">>");
						readIFD(tiffIFD, TiffTag.GPS_SUB_IFD, GPSTag.class, rin, list, ldata[0], indent2);
					} else if((ftag == ExifTag.EXIF_INTEROPERABILITY_OFFSET) && (ldata[0] != 0)) {
						System.out.print(indent);
						System.out.println("<<ExifInteropSubIFD: offset byte " + offset + ">>");
						readIFD(tiffIFD, ExifTag.EXIF_INTEROPERABILITY_OFFSET, InteropTag.class, rin, list, ldata[0], indent2);		
					} else if (ftag == TiffTag.SUB_IFDS) {						
						for(int ifd = 0; ifd < ldata.length; ifd++) {
							System.out.print(indent);
							System.out.println("******* SubIFD " + ifd + " *******");
							readIFD(tiffIFD, TiffTag.SUB_IFDS, TiffTag.class, rin, list, ldata[0], indent2);
							System.out.println("******* End of SubIFD " + ifd + " *******");
						}
					}	
					tiffIFD.addField(new LongField(tag, ldata));
					break;
				case RATIONAL:
					int len = 2*field_length;
					ldata = new int[len];	
					rin.seek(offset);
					int toOffset = rin.readInt();
					offset += 4;					
					for (int j=0;j<len; j+=2){
						rin.seek(toOffset);
						ldata[j] = rin.readInt();
						toOffset += 4;
						rin.seek(toOffset);
						ldata[j+1] = rin.readInt();
						toOffset += 4;
					}	
					tiffIFD.addField(new RationalField(tag, ldata));
					System.out.print(indent);
					System.out.println("Field value: " + StringUtils.rationalArrayToString(ldata, true));
					break;
				case IFD:
					ldata = new int[field_length];
					if(field_length == 1) {
					  rin.seek(offset);
					  ldata[0] = rin.readInt();
					  offset += 4;
					}
					else {
						rin.seek(offset);
						toOffset = rin.readInt();
						offset += 4;
						for (int j=0;j<field_length; j++){
							rin.seek(toOffset);
							ldata[j] = rin.readInt();
							toOffset += 4;
						}
					}
					System.out.print(indent);
					System.out.println("Field value: " + StringUtils.longArrayToString(ldata, true) + " " + ftag.getFieldDescription(ldata[0]&0xffff));
					for(int ifd = 0; ifd < ldata.length; ifd++) {
						System.out.print(indent);
						System.out.println("******* SubIFD " + ifd + " *******");
						readIFD(tiffIFD, TiffTag.SUB_IFDS, TiffTag.class, rin, list, ldata[0], indent2);
						System.out.println("******* End of SubIFD " + ifd + " *******");
					}
					tiffIFD.addField(new IFDField(tag, ldata));			
					break;
				default:
					offset += 4;
					break;					
			}
		}
		// If this is a child IFD, add it to its parent
		if(parent != null)
			parent.addChild(parentTag, tiffIFD);
		else // Otherwise, add to the main IFD list
			list.add(tiffIFD);
		rin.seek(offset);
		
		return rin.readInt();
	}
	
	private static void readIFDs(IFD parent, Tag parentTag, Class<?> tagClass, List<IFD> list, int offset, RandomAccessInputStream rin) throws IOException {
		int ifd = 0;
		// Read the IFDs into a list first	
		while (offset != 0)
		{
			System.out.println("************************************************");
			System.out.println("IFD " + ifd++ + " => offset byte " + offset);
			offset = readIFD(parent, parentTag, tagClass, rin, list, offset, "");
		}
	}
	
	public static void readIFDs(List<IFD> list, RandomAccessInputStream rin) throws IOException {
		int offset = readHeader(rin);
		readIFDs(null, null, TiffTag.class, list, offset, rin);
	}
	
	/**
	 * Remove a range of pages from a multiple page TIFF image
	 * 
	 * @param startPage start page number (inclusive) 
	 * @param endPage end page number (inclusive)
	 * @param is input image stream
	 * @param os output image stream
	 * @return number of pages removed
	 * @throws IOException
	 */
	public static int removePages(int startPage, int endPage, RandomAccessInputStream rin, RandomAccessOutputStream rout) throws IOException {
		if(startPage < 0 || endPage < 0)
			throw new IllegalArgumentException("Negative start or end page");
		else if(startPage > endPage)
			throw new IllegalArgumentException("Start page is larger than end page");
		
		List<IFD> list = new ArrayList<IFD>();
	  
		int offset = copyHeader(rin, rout);
		
		// Step 1: read the IFDs into a list first
		readIFDs(null, null, TiffTag.class, list, offset, rin);		
		// Step 2: remove pages from a multiple page TIFF
		int pagesRemoved = 0;
		if(startPage <= list.size() - 1)  {
			if(endPage > list.size() - 1) endPage = list.size() - 1;
			for(int i = endPage; i >= startPage; i--) {
				if(list.size() > 1) {
					pagesRemoved++;
					list.remove(i);
				}
			}
		}
		// Reset pageNumber for the existing pages
		for(int i = 0; i < list.size(); i++) {
			list.get(i).removeField(TiffTag.PAGE_NUMBER.getValue());
			list.get(i).addField(new ShortField(TiffTag.PAGE_NUMBER.getValue(), new short[]{(short)i, (short)(list.size() - 1)}));
		}
		// End of removing pages		
		// Step 3: copy the remaining pages
		// 0x08 is the first write offset
		int writeOffset = FIRST_WRITE_OFFSET;
		offset = copyPages(list, writeOffset, rin, rout);
		int firstIFDOffset = list.get(0).getStartOffset();

		writeToStream(rout, firstIFDOffset);
		
		return pagesRemoved;
	}
	
	/**
	 * Remove pages from a multiple page TIFF image
	 * 
	 * @param rin input image stream
	 * @param rout output image stream
	 * @param pages an array of page numbers to be removed
	 * @return number of pages removed
	 * @throws IOException
	 */
	public static int removePages(RandomAccessInputStream rin, RandomAccessOutputStream rout, int... pages) throws IOException {
		List<IFD> list = new ArrayList<IFD>();
				  
		int offset = copyHeader(rin, rout);
		
		// Step 1: read the IFDs into a list first
		readIFDs(null, null, TiffTag.class, list, offset, rin);		
		// Step 2: remove pages from a multiple page TIFF
		int pagesRemoved = 0;
		Arrays.sort(pages);
		for(int i = pages.length - 1; i >= 0; i--) {
			if(pages[i] < 0) break;			
			// We have to keep at least one page to avoid corrupting the image
			if(list.size() > 1 && list.size() > pages[i]) {
				pagesRemoved++;
				list.remove(pages[i]); 
			}
		}
		// End of removing pages
		// Reset pageNumber for the existing pages
		for(int i = 0; i < list.size(); i++) {
			list.get(i).removeField(TiffTag.PAGE_NUMBER.getValue());
			list.get(i).addField(new ShortField(TiffTag.PAGE_NUMBER.getValue(), new short[]{(short)i, (short)(list.size() - 1)}));
		}
		// Step 3: copy the remaining pages
		// 0x08 is the first write offset
		int writeOffset = FIRST_WRITE_OFFSET; 
		offset = copyPages(list, writeOffset, rin, rout);
		int firstIFDOffset = list.get(0).getStartOffset();

		writeToStream(rout, firstIFDOffset);
			
		return pagesRemoved;
	}
	
	public static int retainPages(int startPage, int endPage, RandomAccessInputStream rin, RandomAccessOutputStream rout) throws IOException {
		if(startPage < 0 || endPage < 0)
			throw new IllegalArgumentException("Negative start or end page");
		else if(startPage > endPage)
			throw new IllegalArgumentException("Start page is larger than end page");
		
		List<IFD> list = new ArrayList<IFD>();
	  
		int offset = copyHeader(rin, rout);
		
		// Step 1: read the IFDs into a list first
		readIFDs(null, null, TiffTag.class, list, offset, rin);		
		// Step 2: remove pages from a multiple page TIFF
		int pagesRetained = list.size();
		List<IFD> newList = new ArrayList<IFD>();
		if(startPage <= list.size() - 1)  {
			if(endPage > list.size() - 1) endPage = list.size() - 1;
			for(int i = endPage; i >= startPage; i--) {
				newList.add(list.get(i)); 
			}
		}
		if(newList.size() > 0) {
			pagesRetained = newList.size();
			list.retainAll(newList);
		}
		// Reset pageNumber for the existing pages
		for(int i = 0; i < list.size(); i++) {
			list.get(i).removeField(TiffTag.PAGE_NUMBER.getValue());
			list.get(i).addField(new ShortField(TiffTag.PAGE_NUMBER.getValue(), new short[]{(short)i, (short)(list.size() - 1)}));
		}
		// End of removing pages		
		// Step 3: copy the remaining pages
		// 0x08 is the first write offset
		int writeOffset = FIRST_WRITE_OFFSET;
		offset = copyPages(list, writeOffset, rin, rout);
		int firstIFDOffset = list.get(0).getStartOffset();
		
		writeToStream(rout, firstIFDOffset);
		
		return pagesRetained;
	}
	
	// Return number of pages retained
	public static int retainPages(RandomAccessInputStream rin, RandomAccessOutputStream rout, int... pages) throws IOException {
		List<IFD> list = new ArrayList<IFD>();
	  
		int offset = copyHeader(rin, rout);
		// Step 1: read the IFDs into a list first
		readIFDs(null, null, TiffTag.class, list, offset, rin);		
		// Step 2: remove pages from a multiple page TIFF
		int pagesRetained = list.size();
		List<IFD> newList = new ArrayList<IFD>();
		Arrays.sort(pages);
		for(int i = pages.length - 1; i >= 0; i--) {
			if(pages[i] >= 0 && pages[i] < list.size())
				newList.add(list.get(pages[i])); 
		}
		if(newList.size() > 0) {
			pagesRetained = newList.size();
			list.retainAll(newList);
		}
		// End of removing pages
		// Reset pageNumber for the existing pages
		for(int i = 0; i < list.size(); i++) {
			list.get(i).removeField(TiffTag.PAGE_NUMBER.getValue());
			list.get(i).addField(new ShortField(TiffTag.PAGE_NUMBER.getValue(), new short[]{(short)i, (short)(list.size() - 1)}));
		}
		// Step 3: copy the remaining pages
		// 0x08 is the first write offset
		int writeOffset = FIRST_WRITE_OFFSET;
		offset = copyPages(list, writeOffset, rin, rout);
		int firstIFDOffset = list.get(0).getStartOffset();
		
		writeToStream(rout, firstIFDOffset);
		
		return pagesRetained;
	}
	
	public static void snoop(RandomAccessInputStream rin) throws IOException	{	
		System.out.println("*** TIFF snooping starts ***");
		int offset = readHeader(rin);
		List<IFD> list = new ArrayList<IFD>();
		readIFDs(null, null, TiffTag.class, list, offset, rin);
	
		System.out.println("*** TIFF snooping ends ***");
	}
	
	/**
	 * Split a multiple page TIFF into single page TIFFs
	 * 
	 * @param rin input RandomAccessInputStream to read multiple page TIFF
	 * @param outputFilePrefix output file name prefix for the split TIFFs
	 * @throws IOException
	 */
	public static void splitPages(RandomAccessInputStream rin, String outputFilePrefix) throws IOException {
		List<IFD> list = new ArrayList<IFD>();
		short endian = rin.readShort();
		rin.seek(STREAM_HEAD);
		int offset = readHeader(rin);
		readIFDs(null, null, TiffTag.class, list, offset, rin);
		
		String fileNamePrefix = "page_#";
		if(!StringUtils.isNullOrEmpty(outputFilePrefix)) fileNamePrefix = outputFilePrefix + "_" + fileNamePrefix;
		
		for(int i = 0; i < list.size(); i++) {
			RandomAccessOutputStream rout = new FileCacheRandomAccessOutputStream(new FileOutputStream(fileNamePrefix + i + ".tif"));
			// Write TIFF header
			int writeOffset = writeHeader(endian, rout);
			// Write page data
			writeOffset = copyPageData(list.get(i), writeOffset, rin, rout);
			int firstIFDOffset = writeOffset;
			// Write IFD
			if(list.get(i).removeField(TiffTag.SUBFILE_TYPE.getValue()) == null)
				list.get(i).removeField(TiffTag.NEW_SUBFILE_TYPE.getValue());
			list.get(i).removeField(TiffTag.PAGE_NUMBER.getValue());
			list.get(i).addField(new ShortField(TiffTag.SUBFILE_TYPE.getValue(), new short[]{1}));
			writeOffset = list.get(i).write(rout, writeOffset);
			writeToStream(rout, firstIFDOffset);
			rout.close();		
		}
	}
	
	public static void write(TIFFImage tiffImage) throws IOException {
		RandomAccessInputStream rin = tiffImage.getInputStream();
		RandomAccessOutputStream rout = tiffImage.getOutputStream();
		int offset = writeHeader(IOUtils.BIG_ENDIAN, rout);
		offset = copyPages(tiffImage.getIFDs(), offset, rin, rout);
		int firstIFDOffset = tiffImage.getIFDs().get(0).getStartOffset();	
	 
		writeToStream(rout, firstIFDOffset);
	}
	
	// Return stream offset where to write actual image data or IFD	
	private static int writeHeader(short endian, RandomAccessOutputStream rout) throws IOException {
		// Write byte order
		rout.writeShort(endian);
		// Set write strategy based on byte order
		if (endian == IOUtils.BIG_ENDIAN)
		    rout.setWriteStrategy(WriteStrategyMM.getInstance());
		else if(endian == IOUtils.LITTLE_ENDIAN)
		    rout.setWriteStrategy(WriteStrategyII.getInstance());
		else {
			throw new RuntimeException("Invalid TIFF byte order");
	    }		
		// Write TIFF identifier
		rout.writeShort(0x2a);
		
		return FIRST_WRITE_OFFSET;
	}
	
	public static void writeMultipageTIFF(RandomAccessOutputStream rout, TIFFWriter writer, BufferedImage[] images) throws IOException {
		writeMultipageTIFF(rout, writer, null, images);
	}
	
	public static void writeMultipageTIFF(RandomAccessOutputStream rout, TIFFWriter writer, ImageMeta[] imageMeta,  BufferedImage[] images) throws IOException {
		// Write header first
		writeHeader(IOUtils.BIG_ENDIAN, rout);
		// Write pages
		int writeOffset = FIRST_WRITE_OFFSET;
		int pageNumber = 0;
		int maxPageNumber = images.length;
		List<IFD> list = new ArrayList<IFD>(images.length);
		
		ImageMeta[] meta = null;
		
		if(imageMeta == null) {
			meta = new ImageMeta[images.length];
			Arrays.fill(meta, writer.getImageMeta());
		} else if(images.length > imageMeta.length && imageMeta.length > 0) {
				meta = new ImageMeta[images.length];
				System.arraycopy(imageMeta, 0, meta, 0, imageMeta.length);
				Arrays.fill(meta, imageMeta.length, images.length, imageMeta[imageMeta.length - 1]);
		} else {
			meta = imageMeta;
		}
		
		// Grab image pixels in ARGB format and write image
		for(int i = 0; i < images.length; i++) {
			// Retrieve image dimension
			int imageWidth = images[i].getWidth();
			int imageHeight = images[i].getHeight();
			int[] pixels = IMGUtils.getRGB(images[i]);//images[i].getRGB(0, 0, imageWidth, imageHeight, null, 0, imageWidth);
			
			try {
				writer.setImageMeta(meta[i]);
				writeOffset = writer.writePage(pixels, pageNumber++, maxPageNumber, imageWidth, imageHeight, rout, writeOffset);
				list.add(writer.getIFD());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		// Link the IFDs
		for(int i = 0; i < images.length - 1; i++)
			list.get(i).setNextIFDOffset(rout, list.get(i+1).getStartOffset());
				
		int firstIFDOffset = list.get(0).getStartOffset();
		
		writeToStream(rout, firstIFDOffset);
	}
	
	private static void writeToStream(RandomAccessOutputStream rout, int firstIFDOffset) throws IOException {
		// Go to the place where we should write the first IFD offset
		// and write the first IFD offset
		rout.seek(OFFSET_TO_WRITE_FIRST_IFD_OFFSET);
		rout.writeInt(firstIFDOffset);
		// Dump the data to the real output stream
		rout.seek(STREAM_HEAD);
		rout.writeToStream(rout.getLength());
		//rout.flush();
	}
}