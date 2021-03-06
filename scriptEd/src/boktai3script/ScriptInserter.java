package boktai3script;

import java.io.*;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.HashMap;

public class ScriptInserter {
   
   ArrayList lines;
   HashMap codeMap;
   PointerTable ptrTable;
   ScriptMetadata mData;
   ProtectedZones protectedZones;
   
   public ScriptInserter () {
      lines = new ArrayList();
      codeMap = new HashMap(1999);
      ptrTable = new PointerTable();
      mData = new ScriptMetadata();
      protectedZones = new ProtectedZones();
      buildCodeMap("chartable.sjs.tbl");
   }
   
   private byte[] toByteArray(Byte[] a) {
      int len = a.length;
      byte[] b = new byte[len];
      for (int i = 0; i < len; i++) {
         b[i] = a[i].byteValue();
      }
      return b;
   }
   
   public void writeToFile(String fileName) {
      RandomAccessFile file = null;
      try {
         file = new RandomAccessFile(fileName, "rw");
         ptrTable.writeToFile(file, 0xD6B1F8);
         file.seek(0xD74DDC);
      } catch (IOException e) {
         System.err.println("Unable to open file for writing: " + e.getMessage());
         System.exit(1);
      }
      int off;
      ArrayList line;
      ListIterator iter = lines.listIterator();
      int curIdx = 0;
      while (iter.hasNext()) {
         Object tmp = iter.next();
         if (tmp instanceof ArrayList) {
            if ("X".equals(mData.getStatus(curIdx))) {
               curIdx++;
               continue;
            }
            line = (ArrayList) tmp;
            byte[] bytes = toByteArray((Byte[])line.toArray(new Byte[0]));
            try {
               file.write(bytes);
               file.writeBytes("\0");
            } catch (IOException e) {
               System.err.println("Unable to write to file: " + e.getMessage());
               System.exit(1);
            }
            curIdx++;
         } else {
            int bytesToSkip = ((Integer)tmp).intValue();
            try {
               file.skipBytes(bytesToSkip);
            } catch (IOException e) {
               System.err.println("Unable to write to file: " + e.getMessage());
               System.exit(1);
            }
         }
      }
      try {
         file.close();
      } catch (IOException e) {
         System.err.println("Unable to write to file: " + e.getMessage());
         System.exit(1);
      }
   }
   
   public void compileFromFile(String fileName) {
      lines.clear();
      mData = new ScriptMetadata();
      ptrTable = new PointerTable();
      RandomAccessFile file = null;
      try {
         file = new RandomAccessFile(fileName, "r");
      } catch (IOException e) {
         System.err.println("Unable to open file for reading: " + e.getMessage());
         System.exit(1);
      }
      String line = null;
      String status = "";
      int length = 0;
      int totalLength = 0;
      int lineCtr = 0;
      int curLine = 0;
      int numLines = 0;
      ArrayList cScript = new ArrayList();
      boolean firstLine = true;
      try {
         while ((line = file.readLine()) != null) {
            if (line.startsWith("$")) {
               length = cScript.size();
               if (!firstLine) {
                  numLines++;
                  mData.addEntry(status, length);
                  boolean isJunk = ("X".equals(status));
                  if (isJunk) {
                     ptrTable.addEntry(totalLength, isJunk);
                     lines.add(new ArrayList());
                  } else {
                     int newOffset = protectedZones.nextValidOffset(totalLength, totalLength + length);
                     if (newOffset > totalLength) {
                        //System.out.println("Skipping " + (newOffset-totalLength) + " bytes on line " + (lineCtr-1) + " for line length " + (length+1) + " at offset " + totalLength + "...");
                        lines.add(new Integer(newOffset - totalLength));
                        totalLength = newOffset;
                     }
                     ptrTable.addEntry(totalLength, isJunk);
                     lines.add(cScript);
                     totalLength += length + 1;
                  }
               }
               status = line.substring(1,2);
               String[] parts = line.substring(7).split("=====");
               int lineNum = Integer.parseInt(parts[0]);
               if (lineNum != lineCtr) {
                  System.out.println("missing line #" + lineCtr);
               }
               lineCtr++;
               cScript = new ArrayList();
               firstLine = false;
               curLine = 0;
               continue;
            }
            if (line.startsWith("//")) {
               continue;
            }
            if (curLine > 0) {
               cScript.add(new Byte((byte)0x0A));
            }
            if (line.length() == 0) {
               curLine++;
               continue;
            }
            cScript.addAll(parseLine(lineCtr-1, line));
            curLine++;
         }
         if (cScript.size() > 0) {
            numLines++;
            mData.addEntry(status, length);
            boolean isJunk = ("X".equals(status));
            if (isJunk) {
               ptrTable.addEntry(totalLength, isJunk);
               lines.add(new ArrayList());
            } else {
               int newOffset = protectedZones.nextValidOffset(totalLength, totalLength + length);
               if (newOffset > totalLength) {
                  lines.add(new Integer(newOffset - totalLength));
                  totalLength = newOffset;
               }
               ptrTable.addEntry(totalLength, isJunk);
               lines.add(cScript);
               totalLength += length + 1;
            }
         }
      } catch (IOException e) {
         System.err.println("Unable to read from file: " + e.getMessage());
         System.exit(1);
      }
      System.out.println("Lines read from file: " + numLines);
      System.out.println("Total length: " + totalLength + "/400825");
      if (totalLength > 0x61DB9) {
         System.err.println("Error! Total length exceeds maximum alloted space by " + (totalLength-0x61DB9) + " bytes.");
         System.exit(1);
      }
      try {
         file.close();
      } catch (IOException e) {
      }
   }
   
   private ArrayList parseLine(int lnum, String line) {
      ArrayList parsed = new ArrayList();
      ArrayList chr = new ArrayList();
      int idx;
      while (line.length() > 0) {
         if (line.startsWith("{")) {
            idx = line.indexOf("}") + 1;
         } else if (line.charAt(0) > 0x7F) {
            idx = 2;
         } else {
            idx = 1;
         }
         chr.clear();
         for (int i = 0; i < idx; i++) {
            chr.add(new Byte((byte)line.charAt(i)));
         }
         ArrayList tmp = (ArrayList) codeMap.get(chr);
         if (tmp == null) {
            System.out.println("Null warning on line "+lnum+": " + chr);
         }
         parsed.addAll((ArrayList)codeMap.get(chr));
         line = line.substring(idx);
      }
      return parsed;
   }
   
   private void buildCodeMap(String fileName) {
      RandomAccessFile file = null;
      int len = 0;
      byte[] fileBuf = new byte[16750];
      try {
         file = new RandomAccessFile(fileName, "r");
      } catch (IOException e) {
         System.err.println("Unable to open file for reading: " + e.getMessage());
         System.exit(1);
      }
      try {
         len = file.read(fileBuf);
      } catch (IOException e) {
         System.err.println("Unable to read from file: " + e.getMessage());
      }
      int idx = 0;
      while (idx < len) {
         String part1 = "";
         String prev = "";
         ArrayList part2 = new ArrayList();
         while (fileBuf[idx] != '=') {
            part1 += (char)(0xFF & fileBuf[idx++]);
         }
         idx++;
         while (idx < len && fileBuf[idx] != 0x0D) {
            prev += (char)(0xFF & fileBuf[idx]);
            part2.add(new Byte(fileBuf[idx++]));
         }
         idx += 2;
         String chr = part1;
         ArrayList code = new ArrayList();
         while (chr.length() > 0) {
            int bval = Integer.parseInt(chr.substring(0,2),16);
            if (bval > 127) bval -= 256;
            code.add(new Byte((byte)bval));
            chr = chr.substring(2);
         }
         if (prev.equals("$")) {
            part2.clear();
            part2.add(new Byte((byte)0x0A));
         }
         codeMap.put(part2, code);
      }
      try {
         file.close();
      } catch (IOException e) {
      }
   }
   
   public void readProtectedZones(String fileName) {
      protectedZones.readFromFile(fileName);
   }
   
   public void printStats() {
      mData.printStats();
   }
}
