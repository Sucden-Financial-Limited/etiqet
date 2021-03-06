package com.neueda.etiqet.sql.runner;

import cucumber.api.CucumberOptions;
import cucumber.api.junit.Cucumber;
import org.junit.runner.RunWith;

@RunWith(Cucumber.class)
@CucumberOptions(
    plugin = {"pretty", "html:target/cucumber"},
    strict = true,
    monochrome = true,
    features="C:\\Users\\Neueda\\Documents\\Workspace\\etiqet-dev\\etiqet-sql\\src\\test\\resources\\scenarios\\test.feature",
    glue={"com.neueda.etiqet"})
public class Runner {
}
