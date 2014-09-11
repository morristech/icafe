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
import java.io.OutputStream;

import cafe.io.IOUtils;

/**
 * JPEG segment.
 * 
 * @author Wen Yu, yuwen_66@yahoo.com
 * @version 1.0 05/21/2013
 */
public class Segment {

	private Marker marker;
	private int length;
	private byte[] data;
	
	public Segment(Marker marker, int length, byte[] data) {
		this.marker = marker;
		this.length = length;
		this.data = data;
	}
	
	public Marker getMarker() {
		return marker;
	}
	
	public int getLength() {
		return length;
	}
	
	public byte[] getData() {
		return data;
	}
	
	public void write(OutputStream os) throws IOException {
		IOUtils.writeShortMM(os, marker.getValue());
		IOUtils.writeShortMM(os, length);
		IOUtils.write(os, data);	
	}
	
	@Override public String toString() {
		return this.marker.toString();
	}
}