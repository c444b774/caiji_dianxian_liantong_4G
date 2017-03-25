package cn.uway.igp.lte.parser.pm.zte;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.util.Iterator;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.file.CSVParser;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * @author yuy 2013.12.27 lte 中兴性能解码器
 */
public class ZteCSVParser extends CSVParser {

	private static Logger LOGGER = LoggerFactory.getLogger(ZteCSVParser.class);

	public String tableId;

	/**
	 * 输入tar流
	 */
	public TarArchiveInputStream tarInputStream;

	/**
	 * 文件名
	 */
	public String entryName;

	/**
	 * templet对象迭代器
	 */
	public Iterator<String> templetMapIt;

	public ZteCSVParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void before() {
		super.before();

		try {
			// gz解压
			GZIPInputStream gzipstream = new GZIPInputStream(inputStream);
			// tar解压
			tarInputStream = new TarArchiveInputStream(gzipstream);
		} catch (IOException e) {
			throw new IllegalArgumentException("处理中兴性能文件失败", e);
		}
	}

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();

		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());

		// 解析模板 获取当前文件对应的templet
		parseTemplet();
	}

	@Override
	public boolean hasNextRecord() throws Exception {
		try {
			// 如果当前Entry仍然可读 或者还有未读取的Entry 返回true 否则false
			if (reader != null && (currentLine = reader.readLine()) != null) {
				return true;
			}

			TarArchiveEntry entry = null;
			while ((entry = tarInputStream.getNextTarEntry()) != null) {
				if (entry.isDirectory())
					continue;
				entryName = entry.getName();
				// 4923732883459_CELLCDMAHO_20150610_1015-20150610_1030_R2
				String f1 = entryName.substring(entryName.indexOf("_") + 1);
				String fileNameKey = f1.substring(0, f1.indexOf("_"));
				templet = templetMap.get("*"+fileNameKey+"*.csv");
				if (templet == null) {
					templet = templetMap.get(fileNameKey);
				}
				// 匹配要解析的文件
				if (templet != null) {
					// 重新创建一个BufferedReader,原来的BufferedReader等待垃圾回收。不能直接关闭，否则会关闭封装的tarInputStream
					reader = new BufferedReader(new InputStreamReader(tarInputStream, "GBK"), 16 * 1024);

					// 读文件头
					readHead();

					currentLine = reader.readLine();
					if (currentLine == null) {
						continue;
					}

					// 字段定位
					setFieldLocalMap(head);

					return true;
				}
			}
			return false;
		} catch (IOException e) {
			this.cause = "【中兴性能CSV解码】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
	}

	/**
	 * 处理头部
	 * 
	 * @throws Exception
	 */
	public void readHead() throws Exception {
		head = reader.readLine();
		if (head == null) {
			throw new NullPointerException("head is null，解码退出");
		}
		LOGGER.debug("[{}]-获取头line={}", task.getId(), head);
	}

	/**
	 * 解析文件名
	 * 
	 * @throws ParseException
	 */
	public void parseFileName() {
		try {
			String fileName = FileUtil.getFileName(this.rawName);
			String[] str = StringUtil.split(fileName, "_");
			//4g升级，测试联通北向  性能数据时，发现的问题 。个别产家的文件名规则不一定，所以把str.length改为>=6
			if (str.length >= 6) {
				this.tableId = str[1];
				this.currentDataTime = TimeUtil.getyyyyMMdd_HHmmDate(str[4] + "_" + str[5]);
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}

	/**
	 * 解析模板 获取当前文件对应的Templet
	 * 
	 * @throws Exception
	 */
	public void parseTemplet() throws Exception {
		// 解析模板
		TempletParser csvTempletParser = new TempletParser();
		csvTempletParser.tempfilepath = templates;
		csvTempletParser.parseTemp();
		templetMap = csvTempletParser.getTemplets();
	}
}
