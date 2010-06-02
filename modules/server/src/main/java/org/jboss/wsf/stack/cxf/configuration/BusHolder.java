/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.wsf.stack.cxf.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;

import org.apache.cxf.Bus;
import org.apache.cxf.binding.soap.SoapTransportFactory;
import org.apache.cxf.bus.spring.BusApplicationContext;
import org.apache.cxf.configuration.Configurer;
import org.apache.cxf.resource.ResourceManager;
import org.apache.cxf.resource.ResourceResolver;
import org.apache.cxf.transport.DestinationFactory;
import org.apache.cxf.transport.DestinationFactoryManager;
import org.apache.cxf.transport.servlet.ServletTransportFactory;
import org.jboss.logging.Logger;
import org.jboss.ws.Constants;
import org.jboss.wsf.spi.binding.BindingCustomization;
import org.jboss.wsf.stack.cxf.client.configuration.JBossWSCXFConfigurer;
import org.jboss.wsf.stack.cxf.client.configuration.JBossWSSpringBusFactory;
import org.jboss.wsf.stack.cxf.deployment.WSDLFilePublisher;
import org.jboss.wsf.stack.cxf.spring.handler.NamespaceHandlerResolver;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.InputStreamResource;

/**
 * A wrapper of the Bus for performing most of the configurations required on it by JBossWS
 * 
 * @author alessio.soldano@jboss.com
 * @since 25-Mar-2010
 *
 */
public class BusHolder
{
   private static final Logger log = Logger.getLogger(BusHolder.class);
   public static final String PARAM_CXF_BEANS_URL = "jbossws.cxf.beans.url";
   private boolean configured = false;
   protected BusApplicationContext ctx ;
   protected List<GenericApplicationContext> additionalCtx = new LinkedList<GenericApplicationContext>();
   protected Bus bus;
   
   /**
    * Private constructor
    */
   private BusHolder()
   {
      //NOP
   }
   
   /**
    * Creates a new BusHolder instance; a new Bus is created using the
    * provided location for loading additional configurations
    * 
    * @param location
    * @return
    */
   public static BusHolder create(URL location)
   {
      BusHolder holder = new BusHolder();
      holder.createBus(location);
      return holder;
   }
   
   /**
    * Creates a new BusHolder instance using the provided Bus
    * 
    * @param bus
    * @return
    */
   public static BusHolder create(Bus bus)
   {
      BusHolder holder = new BusHolder();
      holder.setBus(bus);
      holder.setContext(bus.getExtension(BusApplicationContext.class));
      return holder;
   }
   
   /**
    * Update the Bus held by the this instance using the provided parameters.
    * This basically prepares the bus for being used with JBossWS.
    * 
    * @param jbossCxfXml    The location of the jboss-cxf.xml configuration file
    * @param soapTransportFactory   The SoapTransportFactory to configure, if any
    * @param resolver               The ResourceResolver to configure, if any
    * @param configurer             The JBossWSCXFConfigurer to install in the bus, if any
    * @throws IOException           Throws IOException if the jboss-cxf.xml file can't be read
    */
   public void configure(URL jbossCxfXml, SoapTransportFactory soapTransportFactory, ResourceResolver resolver, Configurer configurer) throws IOException
   {
      if (configured)
      {
         throw new IllegalStateException("Underlying bus is already configured for JBossWS use!");
      }
      if (configurer != null)
      {
         bus.setExtension(configurer, Configurer.class);
      }
      setSoapTransportFactory(bus, soapTransportFactory);
      setResourceResolver(bus, resolver);
      if (jbossCxfXml != null)
      {
         additionalCtx.add(loadAdditionalConfig(ctx, jbossCxfXml));
      }
      configured = true;
   }
   
   /**
    * A convenient method for getting a jbossws cxf server configurer delegating to the
    * cxf configurer that's currently installed in the hold bus.
    * 
    * @param customization    The binding customization to set in the configurer, if any
    * @param wsdlPublisher    The wsdl file publisher to set in the configurer, if any
    * @return                 The new jbossws cxf configurer
    */
   public Configurer createServerConfigurer(BindingCustomization customization, WSDLFilePublisher wsdlPublisher)
   {
      //the JBossWSCXFConfigurer should already be set through cxf-extensions-jbossws.xml
      ApplicationContext ctx = bus.getExtension(BusApplicationContext.class);
      JBossWSServerCXFConfigurer serverConfigurer = new JBossWSServerCXFConfigurer(ctx);
      serverConfigurer.setBindingCustomization(customization);
      serverConfigurer.setWsdlPublisher(wsdlPublisher);
      return serverConfigurer;
   }
   
   /**
    * Performs close operations (currently implies destroying additional contexts)
    * 
    */
   public void close()
   {
      for (GenericApplicationContext gac : additionalCtx)
      {
         gac.destroy();
      }
   }
   
   /**
    * Creates the Bus using a SpringBusFactory with no specific Spring application context.
    * Then loads additional configurations from the provided location
    * 
    * @param location
    * @return
    */
   protected void createBus(URL location)
   {
      bus = new JBossWSSpringBusFactory().createBus();
      ctx = bus.getExtension(BusApplicationContext.class);
      //Load additional configurations from cxf-servlet.xml
      if (location != null)
      {
         try
         {
            additionalCtx.add(loadAdditionalConfig(ctx, location));
         }
         catch (IOException e)
         {
            if (log.isTraceEnabled())
               log.trace("Could not load additional config from location: " + location, e);
         }
      }
      //Force servlet transport to prevent CXF from using Jetty as a transport
      DestinationFactory factory = new ServletTransportFactory(bus);
      for (String s : factory.getTransportIds()) {
          registerTransport(factory, s);
      }
   }
   
   protected static void setResourceResolver(Bus bus, ResourceResolver resourceResolver)
   {
      if (resourceResolver != null)
      {
         bus.getExtension(ResourceManager.class).addResourceResolver(resourceResolver);
      }
   }
   
   protected static void setSoapTransportFactory(Bus bus, SoapTransportFactory factory)
   {
      if (factory != null)
      {
         DestinationFactoryManager dfm = bus.getExtension(DestinationFactoryManager.class);
         factory.setBus(bus);
         dfm.registerDestinationFactory(Constants.NS_SOAP11, factory);
         dfm.registerDestinationFactory(Constants.NS_SOAP12, factory);
      }
   }
   
   protected static GenericApplicationContext loadAdditionalConfig(ApplicationContext ctx, URL locationUrl) throws IOException
   {
      if (locationUrl == null) throw new IllegalArgumentException("Cannot load additional config from null location!");
      InputStream is = locationUrl.openStream();
      GenericApplicationContext childCtx = new GenericApplicationContext(ctx);
      XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(childCtx);
      reader.setValidationMode(XmlBeanDefinitionReader.VALIDATION_XSD);
      reader.setNamespaceHandlerResolver(new NamespaceHandlerResolver());
      reader.loadBeanDefinitions(new InputStreamResource(is));
      childCtx.refresh();
      return childCtx;
   }

   private void registerTransport(DestinationFactory factory, String namespace)
   {
      bus.getExtension(DestinationFactoryManager.class).registerDestinationFactory(namespace, factory);
   }

   /**
    * Return the hold bus
    * 
    * @return
    */
   public Bus getBus()
   {
      return bus;
   }
   
   private void setBus(Bus bus)
   {
      this.bus = bus;
   }
   
   private void setContext(BusApplicationContext ctx)
   {
      this.ctx = ctx;
   }
}