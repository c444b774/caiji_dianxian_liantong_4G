package cn.uway.igp.lte.parser.cfc.zte;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.accessor.AccessOutObject;
import cn.uway.framework.parser.file.CSVParser;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.util.FileUtil;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

/**
 * lte中兴配置(参数)CSV解码器(全国)
 * 
 * @author yuy
 * @date 2014.4.29
 */
public class ZteCmCSVParser extends CSVParser {

	private static Logger LOGGER = LoggerFactory.getLogger(ZteCmCSVParser.class);

	/**
	 * 输入zip流
	 */
	public ZipInputStream zipstream;

	/**
	 * 文件名
	 */
	public String entryName;

	/**
	 * templet对象迭代器
	 */
	public Iterator<String> templetMapIt;

	public ZteCmCSVParser(String tmpfilename) {
		super(tmpfilename);
	}

	@Override
	public void before() {
		super.before();
		// zip压缩包
		zipstream = new ZipInputStream(inputStream);
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
			// 如果当前ZipEntry仍然可读 或者还有未读取的ZipEntry 返回true 否则false
			if (reader != null && (currentLine = reader.readLine()) != null) {
				return true;
			}

			ZipEntry entry = null;
			while ((entry = zipstream.getNextEntry()) != null) {
				entryName = entry.getName();
				templetMapIt = templetMap.keySet().iterator();
				while (templetMapIt.hasNext()) {
					String file = templetMapIt.next();
					String wildCard = "*";
					// 匹配要解析的文件
					if (wildCardMatch(file, entryName, wildCard)) {
						templet = templetMap.get(file);
						// 重新创建一个BufferedReader,原来的BufferedReader等待垃圾回收。不能直接关闭，否则会关闭封装的ZipStream
						reader = new BufferedReader(new InputStreamReader(zipstream, "GBK"), 16 * 1024);
						// 读文件头
						readHead();
						// 跳过几行
						skipReadLine();

						currentLine = reader.readLine();
						if (currentLine == null) {
							break;
						}
						// 字段定位
						setFieldLocalMap(head);
						return true;
					}
				}
			}
			return false;
		} catch (IOException e) {
			this.cause = "【中兴配置CSV解码】IO读文件发生异常：" + e.getMessage();
			throw e;
		}
	}

	/**
	 * 跳读几行
	 * 
	 * @throws IOException
	 */
	public void skipReadLine() throws IOException {
		if (templet.getSkipLines() != null && templet.getSkipLines() > 0) {
			for (int n = 0; n < templet.getSkipLines(); n++) {
				reader.readLine();
			}
		}
	}

	@Override
	public boolean fieldValHandle(Field field, String str, Map<String, String> map) {
		if (!"true".equals(field.getIsSplit()))
			return true;
		String regex = field.getRegex();
		List<Field> subFieldList = field.getSubFieldList();
		String[] strs = StringUtil.split(str, regex);
		StringBuilder sb = new StringBuilder();
		// 是否有子元素
		boolean isHaveSub = subFieldList != null && subFieldList.size() > 0;
		for (int n = 0; n < strs.length && n < subFieldList.size(); n++) {
			String[] keyValue = StringUtil.split(strs[n], "=");
			String val = keyValue[1];
			if (isHaveSub)
				map.put(subFieldList.get(n).getIndex(), val);
			sb.append(val).append(",");
		}
		// SMOI特殊字段
		map.put("SMOI", sb.substring(0, sb.length() - 1));
		return true;
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
			if (str.length == 5) {
				this.currentDataTime = TimeUtil.getyyyyMMddHHmmDate(str[4]);
			}
		} catch (Exception e) {
			LOGGER.debug("解析文件名异常", e);
		}
	}


}
