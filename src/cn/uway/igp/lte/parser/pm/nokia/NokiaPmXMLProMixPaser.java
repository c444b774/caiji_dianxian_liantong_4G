package cn.uway.igp.lte.parser.pm.nokia;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.util.StringUtil;

/**
 * nokia lte 文件结构变化，解析与老文件不同,<br>
 * 新类重写parse与hasNext逻辑，新文件中属性名<br>
 * 与属性值是各自混合在一起的,参考了中兴xml解析
 */
public class NokiaPmXMLProMixPaser extends NokiaPmXMLParser {

	private static Logger LOGGER = LoggerFactory.getLogger(NokiaPmXMLProMixPaser.class);

	/** 项或属性列表 */
	private String measureProS[] = null;

	/** 项或属性值列表 */
	private String measureValS[] = null;
	
	private boolean hasUnZip = false;

	@Override
	public void parse(AccessOutObject accessOutObject) throws Exception {
		this.accessOutObject = accessOutObject;
		this.before();
		// 解析模板 获取当前文件对应的templet
		parseTemplet();
		LOGGER.debug("开始解码:{}", accessOutObject.getRawAccessName());
		XMLInputFactory fac = XMLInputFactory.newInstance();
		fac.setProperty("javax.xml.stream.supportDTD", false);
		if (accessOutObject.getRawAccessName().endsWith(".tar") || accessOutObject.getRawAccessName().endsWith(".rar")) {
			this.tarGzStream = new TarArchiveInputStream(inputStream);
			this.entry = tarGzStream.getNextTarEntry();
			if (this.entry == null)
				new Exception("解析异常, 文件:" + accessOutObject.getRawAccessName());
			while (!this.entry.isFile()) {
				this.entry = tarGzStream.getNextTarEntry();
				if (this.entry == null)
					new Exception("解析异常, 文件:" + accessOutObject.getRawAccessName());
			}
			this.reader = fac.createXMLStreamReader(new GZIPInputStream(tarGzStream));
			this.parseFileNameGetFileTime(this.entry.getName());
			hasUnZip = true;
		} else if (accessOutObject.getRawAccessName().endsWith(".tar.gz")) {
			this.tarGzStream = new TarArchiveInputStream(new GZIPInputStream(inputStream));
			this.entry = tarGzStream.getNextTarEntry();
			if (this.entry == null)
				new Exception("解析异常, 文件:" + accessOutObject.getRawAccessName());
			while (!this.entry.isFile()) {
				this.entry = tarGzStream.getNextTarEntry();
				if (this.entry == null)
					new Exception("解析异常, 文件:" + accessOutObject.getRawAccessName());
			}
			this.reader = fac.createXMLStreamReader(tarGzStream);
			this.parseFileNameGetFileTime(this.entry.getName());
			hasUnZip = false;
		} else {
			entry = new TarArchiveEntry(new File(accessOutObject.getRawAccessName()));
			this.reader = fac.createXMLStreamReader(new GZIPInputStream(inputStream));
			this.parseFileNameGetFileTime(this.entry.getName());
		}

	}

	/**
	 * 是否有下条数据记录
	 */
	@Override
	public boolean hasNextRecord() throws Exception {
		dataRecordMap = new HashMap<String, String>();
		String measureType = null;
		String tagName = null;
		// 开始的节点名
		String nodeName = null;
		// dn列表
		List<String> dnValList = null;
		int type = -1;
		// 数据节点开关
		boolean flag = false;
		try {
			while (reader.hasNext()) {
				type = reader.next();
				// 只取开始和结束标签
				if (type != XMLStreamConstants.START_ELEMENT && type != XMLStreamConstants.END_ELEMENT)
					continue;
				tagName = reader.getLocalName();
				switch (type) {
					case XMLStreamConstants.START_ELEMENT : {
						// 解析measInfo,得到measurementTypeName
						if (tagName.equalsIgnoreCase("measInfo")) {
							measureType = StringUtil.nvl(reader.getAttributeValue(null, "measInfoId"), "");
							if (this.findMyTemplet(measureType)) {
								nodeName = tagName;
								dnValList = new ArrayList<String>();
								flag = true;
							}
						} else if (tagName.equalsIgnoreCase("measTypes") && flag == true) {
							// 解析measTypes,得到批量属性名
							measureProS = StringUtil.nvl(reader.getElementText(), "").split(" ");
						} else if (tagName.equalsIgnoreCase("measValue") && flag == true) {
							// 解析measValue,得到dn数据,
							dnValList.add(StringUtil.nvl(reader.getAttributeValue(0), ""));
						} else if (tagName.equalsIgnoreCase("measResults") && flag == true) {
							// 解析measValue,得到批量属性值
							measureValS = StringUtil.nvl(reader.getElementText(), "").split(" ");
						}
						break;
					}
					case XMLStreamConstants.END_ELEMENT : {
						if (tagName.equalsIgnoreCase(nodeName) && flag) {
							// 读取完毕，返回
							for (String dnVal : dnValList) {
								String[] strs_comma = StringUtil.split(dnVal, ",");
								for (String dnVal_comma : strs_comma) {
									// DN 信息解析
									String[] strs = StringUtil.split(dnVal_comma, "/");
									int index = -1;
									for (int i = 0; i < strs.length; i++) {
										index = strs[i].indexOf("-");
										String name = strs[i].substring(0, index).toUpperCase();
										if (this.dataRecordMap.get(name) != null) {
											this.dataRecordMap.put("D" + name, strs[i].substring(index + 1, strs[i].length()));
										} else {
											this.dataRecordMap.put(name, strs[i].substring(index + 1, strs[i].length()));
										}
									}
								}
							}
							// measurePro属性名集合与measureVal属性值集合处理,存入dataRecordMap
							for (int i = 0; i < measureProS.length; i++) {
								tagName = measureProS[i];
								if (StringUtil.isEmpty(tagName))
									continue;
								dataRecordMap.put(tagName, measureValS[i]);
							}
							dnValList = null;
							measureProS = null;
							measureValS = null;
							flag = false;
							return true;
						}
						break;
					}
				}
			}
			if (tarGzStream == null)
				return false;
			while ((this.entry = tarGzStream.getNextTarEntry()) != null && this.entry.isFile()) {
				this.parseFileNameGetFileTime(entry.getName());
				XMLInputFactory fac = XMLInputFactory.newInstance();
				fac.setProperty("javax.xml.stream.supportDTD", false);
				if (hasUnZip)
					this.reader = fac.createXMLStreamReader(new GZIPInputStream(tarGzStream));
				else
					this.reader = fac.createXMLStreamReader(tarGzStream);
				return hasNextRecord();
			}
		} catch (XMLStreamException e) {
			this.cause = "【" + myName + "】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
		return false;
	}
}
