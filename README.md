# jocean-svr

#### 介绍
基于 jocean-http & jocean-j2se 的 服务端 RESTFul 框架，部分支持 javax.ws.rs 注解。

#### 软件架构
软件架构说明

依赖 netty 4.x & RxJava 1.x & spring 5.x

#### 安装教程

1. xxxx
2. xxxx
3. xxxx

#### 使用说明

Controll 类示例:

@Path("/demo/")
@Controller
@Scope("singleton")
public class DemoController {
    
    @Path("redirect")
    public ResponseBean redirect() {
        return ResponseUtil.redirectOnly("http://www.baidu.com");
    }

    @Path("hi")
    public Observable<String> hiAsString(@QueryParam("name") final String name, 
            @HeaderParam("User-Agent") final String ua,
            final UntilRequestCompleted<String> urc) {
        return Observable.just("hi, ", name, "'s ", ua).compose(urc);
    }
}

#### 参与贡献


