/**
 * Copyright (c) 2014 by Wen Yu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Any modifications to this file must keep this entire header intact.
 */

package cafe.image.jpeg;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import cafe.io.IOUtils;
import cafe.util.Reader;

/**
 * JPEG DQT segment reader
 *  
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 10/11/2013
 */
public class DQTReader implements Reader {

	private Segment segment;
	private List<QTable> qTables = new ArrayList<QTable>(4);
	
	public DQTReader(Segment segment) throws IOException {
		//
		if(segment.getMarker() != Marker.DQT) {
			throw new IllegalArgumentException("Not a valid DQT segment!");
		}
		
		this.segment = segment;
		read();
	}
	
	public List<QTable> getTables() {
		return qTables;
	}
	
	@Override
	public void read() throws IOException {
		//
		byte[] data = segment.getData();		
		int len = segment.getLength();
		len -= 2;//
		
		int offset = 0;
		
	  	int[] de_zig_zag_order = JPEGConsts.getDeZigzagMatrix();
		  
		while(len > 0)
		{
			int QT_info = data[offset++];
			len--;
		    int QT_precision = (QT_info>>4)&0x0f;
		    int QT_index=(QT_info&0x0f);
		    
		    short[] out = (QT_precision == 0)?new short[64] : new short[128];
		   
		    // Read QT tables
    	    // 8 bit For precision value of 0
		   	if(QT_precision == 0) {
				for (int j = 0; j < 64; j++)
			    {
					out[j] = data[de_zig_zag_order[j] + offset];			
			    }
			} else { // 16 bit big-endian for precision value of 1
								
				for (int j = 0; j < 64; j++) {
					out[j] = (IOUtils.readShortMM(data, offset + de_zig_zag_order[j]<<1));	
				}				
			}
		   	
		   	qTables.add(new QTable(QT_precision, QT_index, out));
		
			len -= (QT_precision == 0)?64:128;
			offset += (QT_precision == 0)?64:128;
		}
	}	
}