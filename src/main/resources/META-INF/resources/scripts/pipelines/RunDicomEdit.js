/* 
 * Copyright 2018 Radiologics, Inc
 * Developer: James Dickson @radiologics.com
 * Dialog to edit dicom header information
 */
XNAT.app.DicomEdit={
	resource_label:"",
	project:"",
	source:"",
	existingJsonString:"",
	expt_id:"",
	show:function(){
		XNAT.app.DicomEdit.doTable();
		XNAT.app.DicomEdit.dialog.render(document.body);
		XNAT.app.DicomEdit.dialog.show();
		XNAT.app.DicomEdit.filter();
	},
	doLaunch:function(){
		var myJsonString = "" + this.generateRecordSetJSON(this.dialog.dicomDataTable.getRecordSet()) + "";
		var changedJsonArray=this.changedJson(myJsonString)
		if (changedJsonArray.length > 0){
		xModalConfirm({
			  message: "Confirm",
		      content: "All selected DICOM files will be edited. Please confirm before continuing.",
		      okAction: function(){
		    	  XNAT.app.DicomEdit.doActualLaunch(changedJsonArray);
		      },
		      cancelAction: function(){
		      }
		    });
		}else{
	        xModalMessage('Alert', 'Nothing has changed.');
		}
	},
	doActualLaunch: function (changedJsonArray) {
		let scans = $('input[name="scan"]').map(function() {
			return $( this ).val();
		}).get();

		let formData = {
			JSON: "'" + JSON.stringify(changedJsonArray, null, " ").replace(/\n/g, '') + "'",
			scan: JSON.stringify(scans),
			session: "/archive/experiments/" + this.expt_id
		};

		openModalPanel("val_DicomEdit_mdl","Launching DICOM edit container...");
		var dedit = this;
		XNAT.xhr.getJSON({
			url: XNAT.url.rootUrl('/xapi/commands'),
			data: {name: 'dicomedit'},
			success: function (data) {
				if (data.length === 0) {
					dedit.handleFailure({responseText: "No dicomedit command available on this site"});
				}
				if (data.length > 1) {
					dedit.handleFailure({
						responseText: "Multiple dicomedit commands available on this site, " +
							"unsure which to use"
					});
				}
				let cmd = data[0];
				XNAT.xhr.postJSON({
					url: XNAT.url.rootUrl("/xapi/projects/" + dedit.project + "/commands/" +
						cmd.id + "/wrappers/dicomedit/root/session/launch"),
					data: JSON.stringify(formData),
					success: function(resp) {
						closeModalPanel("val_DicomEdit_mdl");
						dedit.handleCompletion(resp);
					},
					error: function(e) {
						closeModalPanel("val_DicomEdit_mdl");
						dedit.handleFailure(e);
					}
				});
			},
			error: function (e) {
				dedit.handleFailure(e);
			}
		});
	},
	changedJson:function(jsonDoc){
		var jsonData = JSON.parse(jsonDoc);
		var newJsonData=[]		
		for (var i = 0; i < jsonData.length; i++) {
		    var dicom = jsonData[i];
		   
		    if(dicom.value!="" && dicom.value!=dicom.existing){
		    	var newdicom = {"tag1": dicom.tag1,"value": dicom.value,"existing": dicom.existing}
		    	newJsonData.push(newdicom)
		    }
		}
		return newJsonData;
	},
	handleCompletion:function(response){
		openModalPanel("val_dcm_comp","DICOM edit container launched", null,
			{"body":"DICOM edit container launched successfully. This page will reload.",width:"360px",height:"100px"});
		this.dialog.hide();
		setTimeout(function(){window.location.reload();}, 3000);
		
	},
	generateRecordSetJSON : function(p_oRecordset) {
		  var arr = [];

		  for (var i = 0, len = p_oRecordset.getLength();i < len;i++) {
		  	let tagInfo = p_oRecordset.getRecord(i).getData();
		  	tagInfo.tag1 = tagInfo.tag1.replace(/\(([0-9]+,[0-9]+)\)/g, "$1"); // strip () from tag so CS doesn't complain
		    arr.push(JSON.stringify(tagInfo));
		  }

		  return '[' + arr.join(',') + ']';
	},
	removeBasedOnWhitelist : function(p_oRecordset, p_oWhitelist) {
		  var arr = [];
		 
		  for (var i = 0, len = p_oRecordset.getLength();i < len;i++) {
			  tag=p_oRecordset.getRecord(i).getData("Tag");
			  if (tag !="(0002,0001)"){
				  theDataTable.deleteRow(p_oRecordset.getRecordIndex(i))
			  }
		  }

		 
	},
	
	handleFailure:function(response){
		openModalPanel("val_dcm_comp","Failed to launch DICOM edit container",null,{"body":response.responseText,width:"360px",height:"240px"});

		this.dialog.hide();
	},
	doTable:function(){
		this.dialog.dicomColumnDefs=[
		     						   	  {key:"tag1",label:"Tag",sortable:true,width:"50px"},
		     						   	  {key:"desc",label:"Description",sortable:true,width:"100px"},
		     						   	  {key:"existing",label:"Existing Value",sortable:true,width:"400px"},
		     						   	  {key:"value",editor:new YAHOO.widget.TextareaCellEditor(),label:"New Value",sortable:true,width:"400px"}
		     						   	  ];
		     						     
		     						     
		     						   
		this.dialog.dicomDataSource = new YAHOO.util.DataSource(serverRoot + "/data/services/dicomeditdump?");
		this.dialog.dicomDataSource.responseType = YAHOO.util.DataSource.TYPE_JSON;
		this.dialog.dicomDataSource.responseSchema = {
		     						        resultsList : "ResultSet.Result",
		     						        fields: ["tag2","existing","desc","value","vr","tag1"]
		     						      };
		     						           
		this.dialog.dicomDataTable = new YAHOO.widget.DataTable("dicom_table", this.dialog.dicomColumnDefs,this.dialog.dicomDataSource,{scrollable:"y",width:"600px",height:"500px",initialRequest:"src="+this.source+"&summary=true&format=json"});
		// Set up editing flow 
		// Set up editing flow 
		//delete using whitelist
	    this.dialog.dicomDataTable.subscribe("cellMouseoverEvent", this.highlightEditableCell); 
		this.dialog.dicomDataTable.subscribe("cellMouseoutEvent", this.dialog.dicomDataTable.onEventUnhighlightCell); 
		this.dialog.dicomDataTable.subscribe("cellClickEvent", this.dialog.dicomDataTable.onEventShowCellEditor);   
		     		
		
		this.dialog.render();
	},
	  highlightEditableCell:function(oArgs) { 
         var elCell = oArgs.target; 
         if(YAHOO.util.Dom.hasClass(elCell, "yui-dt-editable")) { 
        	 this.existingJsonString = "" + this.generateRecordSetJSON(this.dialog.dicomDataTable.getRecordSet()) + "";
             this.highlightCell(elCell); 
         } 
     },
     filter:function(){
    	//this.removeBasedOnWhitelist(this.dialog.dicomDataTable.getRecordSet());
     }
}


//initialize modal upload dialog

XNAT.app.DicomEdit.dialog=new YAHOO.widget.Dialog("lnch_DicomEdit_dialog", {zIndex:999, fixedcenter:true, visible:false, width:"900px", close:true, draggable:true } ),
XNAT.app.DicomEdit.dialog.cfg.queueProperty("buttons", [{ text:"Cancel", handler:{fn:function(){XNAT.app.DicomEdit.dialog.hide();}}},{ text:"Launch", handler:{fn:function(){XNAT.app.DicomEdit.doLaunch();}}, isDefault:true}]);
