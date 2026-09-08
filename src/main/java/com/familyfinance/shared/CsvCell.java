package com.familyfinance.shared;
public final class CsvCell {
 private CsvCell(){}
 public static String escape(String value){
  if(value==null)return "";
  String leading=value.stripLeading();
  if(!leading.isEmpty()&&"=+-@".indexOf(leading.charAt(0))>=0||!value.isEmpty()&&"\t\r\n".indexOf(value.charAt(0))>=0)value="'"+value;
  return value.indexOf(',')>=0||value.indexOf('"')>=0||value.indexOf('\r')>=0||value.indexOf('\n')>=0?"\""+value.replace("\"","\"\"")+"\"":value;
 }
}
