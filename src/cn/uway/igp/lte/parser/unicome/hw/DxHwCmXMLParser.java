package cn.uway.igp.lte.parser.unicome.hw;

import java.text.DecimalFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.uway.framework.parser.ParseOutRecord;
import cn.uway.framework.parser.file.templet.Field;
import cn.uway.util.StringUtil;
import cn.uway.util.TimeUtil;

public class DxHwCmXMLParser extends HwCmXMLParser{

	private static Logger LOGGER = LoggerFactory.getLogger(DxHwCmXMLParser.class);
	
	protected static final String BTS3900NE = "BTS3900NE";
	
	protected static final String BTS3900LOCATION = "BTS3900LOCATION";
	
	public DecimalFormat d6f = new DecimalFormat("0.000000");
	
	// 临时存放BTS3900NE的数据，其经纬度数据从BTS3900LOCATION节点中LATITUDEDEGFORMAT、LONGITUDEDEGFORMAT获取
	public Map<String, String> dataMap = null;
	
	
	public DxHwCmXMLParser(String tmpfilename) {
		super(tmpfilename);
		// TODO Auto-generated constructor stub
	}
	
	@Override
	public boolean hasNextRecord() throws Exception {
		int index = -1;
		String lineStr = "";
		try {
			while ((lineStr = this.reader.readLine()) != null) {
				if ((index = lineStr.indexOf(MO_TAG)) > -1) {
					this.lastClassName = this.className;
					this.className = lineStr.substring(index + MO_TAG.length(), lineStr.indexOf("\" "));
					if ((this.lastClassName != null && this.lastClassName.length()>0) && findMyTemplet(this.lastClassName)) {
						// 根据运营商标识过滤数据,只有0 2时，按照运行商标识过滤，其余的不做过滤
						if(operatorsMarFlag && this.recordDataMap.get("CNOPERATORID") != null
								&& !operatorsMar.equals(this.recordDataMap.get("CNOPERATORID")))
						{
							this.recordDataMap = null;
							continue;
						}
						// 临时存放BTS3900NE的数据，其经纬度数据从BTS3900LOCATION节点中LATITUDEDEGFORMAT、LONGITUDEDEGFORMAT获取
						// BTS3900NE节点数据最后返回
						if(BTS3900NE.equals(this.lastClassName)){
							this.dataMap = this.recordDataMap;
							this.dataMap.put("LATITUDE", null);
							this.dataMap.put("LONGITUDE", null);
							this.recordDataMap = null;
							continue;
						}else if(BTS3900LOCATION.equals(this.lastClassName) && 
								(StringUtil.isEmpty(this.dataMap.get("LATITUDE")) 
								|| StringUtil.isEmpty(this.dataMap.get("LONGITUDE")))){
							this.dataMap.put("LATITUDE", this.recordDataMap.get("LATITUDEDEGFORMAT"));
							this.dataMap.put("LONGITUDE", this.recordDataMap.get("LONGITUDEDEGFORMAT"));
						}
						isNodeB = this.lastClassName.endsWith(NODEB_MO);
						return true;
					} else {
						this.recordDataMap = null;
					}
				} else if ((index = lineStr.indexOf(ATTR_TAG)) > -1) {
					if (this.recordDataMap == null)
						this.recordDataMap = new HashMap<String, String>();
					String attrName = lineStr.substring(index + ATTR_TAG.length(), lineStr.indexOf("\">")).toUpperCase();
					String attrVal = lineStr.substring(lineStr.indexOf("\">") + 2, lineStr.indexOf("</attr>"));
					this.recordDataMap.put(attrName,attrVal);
				}
			}
			// 最后一条数据
			if (this.recordDataMap != null && this.recordDataMap.size() > 0) {
				this.lastClassName = this.className;
				this.className = null;
				if (findMyTemplet(this.lastClassName)) {
					isNodeB = this.lastClassName.endsWith(NODEB_MO);
					return true;
				} else {
					this.recordDataMap = null;
				}
			}
			if(this.dataMap != null && this.dataMap.size() > 0){
				isNodeB = false;
				this.lastClassName = BTS3900NE;
				this.className = null;
				if (findMyTemplet(this.lastClassName)) {
					this.recordDataMap = this.dataMap;
					this.dataMap = null;
					return true;
				} else {
					this.recordDataMap = null;
				}
			}
		} catch (Exception e) {
			this.cause = lineStr + ":【华为参数xml解码】IO读文件发生异常：" + e.getMessage();
			LOGGER.debug(cause);
			throw e;
		}
		return false;
	}

	@Override
	public ParseOutRecord nextRecord() throws Exception {
		readLineNum++;
		ParseOutRecord record = new ParseOutRecord();
		List<Field> fieldList = this.templet.getFieldList();
		Map<String, String> recordData = this.createExportPropertyMap(this.templet.getDataType());
		for (Field field : fieldList) {
			if (field == null) {
				continue;
			}
			String value = this.recordDataMap.get(field.getName().toUpperCase());
			// 找不到，设置为空
			if (value == null) {
				continue;
			}
			if ("DAYLIGHTSAVEINFO".equals(field.getName())) {
				continue;
			}
			recordData.put(field.getIndex(), value);
			// 是否拆封字段
			if ("true".equals(field.getIsSplit())) {
				// FDN 拆封
				if ("FDN".equalsIgnoreCase(field.getName())) {
					if (!this.splitFDNStr(value, field, recordData)) {
						LOGGER.error("节点：{}，fdn：{}，拆封失败。", new Object[]{this.className, value});
						this.invalideNum++;
						this.recordDataMap = null;
						return null;
					}
				} else if ("NAME".equalsIgnoreCase(field.getName())) {
					// NAME 拆封
					if (!this.splitNameStr(value, field, recordData)) {
						LOGGER.error("节点：{}，name：{}，拆封失败。", new Object[]{this.className, value});
						this.invalideNum++;
						this.recordDataMap = null;
						return null;
					}
				}
			}
		}
		// 公共回填字段
		recordData.put("MMEID", String.valueOf(task.getExtraInfo().getOmcId()));
		recordData.put("COLLECTTIME", TimeUtil.getDateString(new Date()));
		// 把任务表中的BSC_ID字段添加到内存中 @author Niow 2014-6-16
		recordData.put("BSCID", String.valueOf(task.getExtraInfo().getBscId()));
		handleTime(recordData);
		
		if (SRC_ENBCELL_CLASSNAME.equalsIgnoreCase(templet.getDataName())) {
			this.s_enodebid = recordData.get("NEID");
			this.s_cellid = recordData.get("CELLID");
		} else {
			recordData.put("S_ENODEBID", this.s_enodebid);
			recordData.put("S_CELLID", this.s_cellid);
		}
		// 经纬度换算
		if(BTS3900NE.equals(this.lastClassName) 
				&& StringUtil.isNotEmpty(recordData.get("LATITUDE")) 
				&& StringUtil.isNotEmpty(recordData.get("LONGITUDE"))){
			recordData.put("LATITUDE", d6f.format(Double.valueOf(recordData.get("LATITUDE"))/1000000D));
			recordData.put("LONGITUDE", d6f.format(Double.valueOf(recordData.get("LONGITUDE"))/1000000D));
		}
		
		if(isNodeB){
			String nodeBName = recordData.get(NODEB_NAME);
			String ul = uls.get(nodeBName);
			if(null == ul){
				ul = CacheManager.getUserLabel(task, job, nodeBName);
				uls.put(nodeBName, ul);
			}
			recordData.put("USERLABEL",ul);
		}
		record.setType(templet.getDataType());
		record.setRecord(recordData);
		this.recordDataMap = null;
		return record;
	}
}
