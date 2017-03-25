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
public class LucCmXmlTempletParser extends TempletParser {

	/** <parentIds(对应parentIds属性)> */
	public Map<String, String[]> parentIdsMap = new HashMap<String, String[]>();

	/**
	 * 处理file属性
	 * 
	 * @param templet
	 * @param templetNode
	 * @throws Exception
	 */
	public void personalHandler(Templet templet, Node templetNode) throws Exception {
		Node tagNameNode = templetNode.getAttributes().getNamedItem("tagName");
		if (tagNameNode != null) {
			String tagName = tagNameNode.getNodeValue();
			if (StringUtil.isEmpty(tagName))
				throw new Exception("tagName属性值不能为空");
			templet.setDataName(tagName.trim());
		} else
			throw new Exception("缺少tagName属性");
		Node parentIdsNode = templetNode.getAttributes().getNamedItem("parentIds");
		if (parentIdsNode != null) {
			String parentIds = parentIdsNode.getNodeValue();
			if (StringUtil.isEmpty(parentIds))
				throw new Exception("parentIds属性值不能为空");
			String[] array = StringUtil.split(parentIds, ",");
			parentIdsMap.put(templet.getDataName(), array);
		}
	}
}
