package cn.uway.igp.lte.parser.pm.hw;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;


public class HwPmXMLAdaptParser   extends HwPmZipXMLParser {

	private static Logger LOGGER = LoggerFactory.getLogger(HwPmXMLAdaptParser.class);

	public HwPmXMLAdaptParser() {
		
	}
	
	public HwPmXMLAdaptParser(String tmpfilename) {
		super(tmpfilename);
	}
	
	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.zipFileName = this.accessOutObject.getRawAccessPackName();// 压缩文件
		this.before();
		LOGGER.debug("开始解码:{}", this.rawName);
		// 解析模板 获取当前文件对应的templet
		if (this.templetMap == null || templetMap.size() < 1) {
			this.parseTemplet();
		}
		
		if (this.templetMap.isEmpty()) {
			LOGGER.warn("华为性能xml文件未读取到解析模板信息。");
			return;
		}
		
		this.rawName = this.accessOutObject.getRawAccessName();
		// 解析文件名
		this.parseFileName();
		this.beginTime = null;
		// 转换为缓冲流读取
		this.reader = new BufferedReader(new InputStreamReader(this.inputStream, "GBK"), 16 * 1024);
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		return this.readRecord();
	}
}