package cn.uway.igp.lte.parser.cdt;

import cn.uway.framework.parser.ParseOutRecord;

/**
 * 和ZteCoreNetCsvFileParser相比只是nextRecord()返回为空,用于不需要返回值的处理方式
 * @author tylerlee
 * @ 2016年11月21日
 */
public class ZteCoreNetCsvFileWithoutRecordParser extends ZteCoreNetCsvFileParser {
	@Override
	public ParseOutRecord nextRecord() throws Exception {
		handleNextRecord();
		return null;
	}
}
