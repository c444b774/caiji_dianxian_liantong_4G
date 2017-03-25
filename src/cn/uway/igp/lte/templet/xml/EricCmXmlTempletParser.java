package cn.uway.igp.lte.templet.xml;

import java.util.HashMap;
import java.util.Map;

import org.w3c.dom.Node;

import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.StringUtil;

/**
 * @author yuy @ 23 May, 2014
 */
public class EricCmXmlTempletParser extends TempletParser {

	/** <indexs(对应indexs属性)> */
	public Map<String, String[]> indexsMap = new HashMap<String, String[]>();

	/**
	 * 处理file属性
	 * 
	 * @param templet
	 * @param templetNode
	 * @throws Exception
	 */
	public void personalHandler(Templet templet, Node templetNode) throws Exception {
		Node vsDataTypeNode = templetNode.getAttributes().getNamedItem("vsDataType");
		if (vsDataTypeNode != null) {
			String vsDataType = vsDataTypeNode.getNodeValue();
			if (StringUtil.isEmpty(vsDataType))
				throw new Exception("vsDataType属性值不能为空");
			templet.setDataName(vsDataType.trim().toUpperCase());
		} else
			throw new Exception("缺少vsDataType属性");
		Node indexsNode = templetNode.getAttributes().getNamedItem("indexs");
		if (indexsNode != null) {
			String indexs = indexsNode.getNodeValue();
			if (StringUtil.isEmpty(indexs))
				throw new Exception("indexs属性值不能为空");
			String[] array = StringUtil.split(indexs, ",");
			indexsMap.put(templet.getDataName(), array);
		} else
			throw new Exception("缺少indexs属性");
	}
}
