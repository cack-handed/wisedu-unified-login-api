package wiki.zimo.wiseduunifiedloginapi.process;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import net.sourceforge.tess4j.TesseractException;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import wiki.zimo.wiseduunifiedloginapi.builder.IapLoginEntityBuilder;
import wiki.zimo.wiseduunifiedloginapi.entity.IapLoginEntity;
import wiki.zimo.wiseduunifiedloginapi.helper.ImageHelper;
import wiki.zimo.wiseduunifiedloginapi.helper.TesseractOCRHelper;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 金智统一iap登陆
 */
public class IapLoginProcess {
    private IapLoginEntity loginEntity;
    private Map<String, String> params;

    public IapLoginProcess(String loginUrl, Map<String, String> params) {
        this.loginEntity = new IapLoginEntityBuilder().loginUrl(loginUrl).build();
        this.params = params;
    }

    public Map<String, String> login() throws Exception {
        // 请求登陆页
        Connection con = Jsoup.connect(loginEntity.getLoginUrl()).followRedirects(true);
        Connection.Response res = con.execute();

        // 构造请求头
        Map<String, String> headers = new HashMap<>();
        String reffer = res.url().toString();
//        System.out.println("reffer:" + reffer);
        String host = res.url().getHost();
//        System.out.println("host:" + host);
        String protocol = res.url().getProtocol();
        String origin = protocol + "://" + res.url().getHost();
//        System.out.println("origin:" + origin);
        headers.put("Host", host);
        headers.put("Connection", "keep-alive");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("X-Requested-With", "XMLHttpRequest");
//        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.61 Safari/537.36");
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 4.4.4; OPPO R11 Plus Build/KTU84P) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/33.0.0.0 Safari/537.36");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Origin", origin);
//        headers.put("Sec-Fetch-Site", "same-origin");
//        headers.put("Sec-Fetch-Mode", "cors");
//        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Referer", reffer);
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9");

        // 全局cookie
        Map<String, String> cookies = res.cookies();

        String username = this.params.get("username");
        String password = this.params.get("password");

        // 构造请求参数
        Map<String, String> params = new HashMap<>();
        params.put("username", username);
        params.put("password", password);
        params.put("rememberMe", String.valueOf(false));
        params.put("mobile", "");
        params.put("dllt", "");

        // 申请It
        String itUrl = loginEntity.getItUrl();
//        System.out.println(itUrl);
        Document doc = Jsoup.connect(itUrl).ignoreContentType(true).cookies(cookies).post();
//        System.out.println(doc);
        JSONObject jsonObject = JSON.parseObject(doc.body().text());
        if (jsonObject.getInteger("code") != 200) {
            throw new RuntimeException("申请It失败");
        }
        JSONObject result = jsonObject.getJSONObject("result");
        String it = result.getString("_lt");
        params.put("lt", it);

        // 拿到encryptSalt
        String encryptSalt = result.getString("_encryptSalt");
        // 密码暂时不需要加密，不排除后面需要加密的可能
//        params.put("password", AESHelper.encryptAES(password, encryptSalt));
//        System.out.println(params);

        // 登陆地址处理
        String login_url = loginEntity.getDoLoginUrl();
//        System.out.println(login_url);

        // 是否需要验证码地址处理
        String needcaptcha_url = loginEntity.getNeedcaptchaUrl() + "?username=" + username;
//        System.out.println(needcaptcha_url);

        // 模拟登陆之前首先请求是否需要验证码接口
        doc = Jsoup.connect(needcaptcha_url).cookies(cookies).ignoreContentType(true).get();
        Boolean needCaptcha = Boolean.valueOf(JSON.parseObject(doc.body().text()).getString("needCaptcha"));
//        System.out.println(needCaptcha);
        if (needCaptcha) {
            // 验证码处理，最多尝试10次
            int time = 10;
            while (time-- > 0) {
                String captcha_url = loginEntity.getCaptchaUrl() + "?ltId=" + it;
//                System.out.println(captcha_url);
                // 识别验证码
                String code = ocrCaptcha(cookies, captcha_url);
                params.put("captcha", code);
//                System.out.println(code);

                // 模拟登陆
                return iapSendLoginData(login_url, headers, cookies, params);
            }
        } else {
            params.put("captcha", "");

            // 模拟登陆
            return iapSendLoginData(login_url, headers, cookies, params);
        }

        return null;
    }

    /**
     * iap发送登陆请求，返回cookies
     *
     * @param login_url
     * @param headers
     * @param cookies
     * @param params
     * @return
     * @throws Exception
     */
    private Map<String, String> iapSendLoginData(String login_url, Map<String, String> headers, Map<String, String> cookies, Map<String, String> params) throws Exception {
        try {
            Connection con = Jsoup.connect(login_url).headers(headers).ignoreContentType(true).followRedirects(false).cookies(cookies).data(params).method(Connection.Method.POST);
            Connection.Response res = con.execute();
            // 更新cookie
            cookies.putAll(res.cookies());
            // 修复新乡医学院等iap登陆方式可能被多次重定向的问题
            String body = res.body();
            System.out.println(res.headers());
            if (body.contains("307")) {
                String location = res.headers().get("Location");
//                System.out.println(location);
                res = Jsoup.connect(location).headers(headers).ignoreContentType(true).followRedirects(true).cookies(cookies).data(params).method(Connection.Method.POST).execute();
//                System.out.println(res.headers());
//                System.out.println(res.cookies());
//                System.out.println(res.body());
                // 更新cookies
                cookies.putAll(res.cookies());
                // 更新body
                body = res.body();
            }
            // 然后就是正常流程
            JSONObject jsonObject = JSON.parseObject(body);
//            System.out.println(jsonObject.toString());
            String resultCode = jsonObject.getString("resultCode");
            if (!resultCode.equals("REDIRECT")) {
                throw new RuntimeException("用户名或密码错误");
            }
            // 第一次重定向，手动重定向
            String url = headers.get("Origin") + jsonObject.getString("url");
            // 后面会有多次重定向，所以开启自动重定向
            res = Jsoup.connect(url).cookies(cookies).followRedirects(true).ignoreContentType(true).execute();
            // 再次更新cookie，防爬策略：每个页面一个cookie
            cookies.putAll(res.cookies());
            // 登陆成功
            return cookies;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * 处理验证码识别
     *
     * @param cookies
     * @param captcha_url
     * @return
     * @throws IOException
     * @throws TesseractException
     */
    private String ocrCaptcha(Map<String, String> cookies, String captcha_url) throws IOException, TesseractException {
        while (true) {
            String filePach = System.getProperty("user.dir") + File.separator + System.currentTimeMillis() + ".jpg";
//            System.out.println(filePach);
            Connection.Response response = Jsoup.connect(captcha_url).cookies(cookies).ignoreContentType(true).execute();

            // 五位验证码，背景没有噪点
            ImageHelper.saveImageFile(response.bodyStream(), filePach);
            String s = TesseractOCRHelper.doOcr(filePach);

            File temp = new File(filePach);
            temp.delete();
            if (judge(s, 5)) {
                return s;
            }
        }
    }

    /**
     * 判断ocr识别出来的结果是否符合条件
     *
     * @param s
     * @param len
     * @return
     */
    private boolean judge(String s, int len) {
        if (s == null || s.length() != len) {
            return false;
        }

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!(Character.isDigit(ch) || Character.isLetter(ch))) {
                return false;
            }
        }

        return true;
    }
}
