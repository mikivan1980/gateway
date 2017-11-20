package com.mikivan.gateway;

import javax.ws.rs.*;


@Path("controller")
public class Controller {


    @GET
    @Path("name")
    public String getName(){
        return "hello from mikivan!!!";
    }

}
