package cn.uway.igp.lte.templet.xml;

import org.w3c.dom.Node;

import cn.uway.framework.parser.file.templet.Templet;
import cn.uway.framework.parser.file.templet.TempletParser;
import cn.uway.util.StringUtil;

/**
 * @author yuy 2014.1.6 xml cfc模板解码器
 */
public class HwPmXmlTempletParser extends TempletParser {

	/**
	 * 处理file属性
	 * 
	 * @param templet
	 * @param templetNode
	 * @throws Exception
	 */
	public void personalHandler(Templet templet, Node templetNode) throws Exception {
		Node measInfoIdNode = templetNode.getAttributes().getNamedItem("measInfoId");
		if (measInfoIdNode != null) {
			String measInfoId = measInfoIdNode.getNodeValue();
			if (StringUtil.isEmpty(measInfoId))
				throw new Exception("measInfoId属性值不能为空");
			templet.setDataName(measInfoId.trim());
		} else
			throw new Exception("缺少measInfoId属性");
	}

}
