/*
** Copyright (C) 2013 Mellanox Technologies
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at:
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
** either express or implied. See the License for the specific language
** governing permissions and  limitations under the License.
**
*/

#include "bullseye.h"
#include "Utils.h"
#include "MsgPool.h"

//TODO: make sure that in and out size are aligned to 64!!!!

#define MODULE_NAME		"MsgPool"
#define MSGPOOL_LOG_ERR(log_fmt, log_args...)  LOG_BY_MODULE(lsERROR, log_fmt, ##log_args)
#define MSGPOOL_LOG_WARN(log_fmt, log_args...) LOG_BY_MODULE(lsWARN, log_fmt, ##log_args)
#define MSGPOOL_LOG_DBG(log_fmt, log_args...)  LOG_BY_MODULE(lsDEBUG, log_fmt, ##log_args)


MsgPool::MsgPool(int msg_num, int in_size, int out_size)
{
	Msg* msg = NULL;
	this->in_size = in_size;
	this->out_size = out_size;
	this->msg_num = msg_num;
	this->msg_ptrs = NULL;
	this->xio_mr = NULL;
	this->buf_size = msg_num * (in_size + out_size);

	this->x_buf = xio_alloc(buf_size);
	BULLSEYE_EXCLUDE_BLOCK_START
	if (this->x_buf == NULL) {
		MSGPOOL_LOG_WARN("there was an error while allocating & registering memory via huge pages");
		MSGPOOL_LOG_WARN("You should work with Mellanox OFED 2.0 or newer");
		MSGPOOL_LOG_WARN("attempting to allocate&registering memory. THIS COULD HURT PERFORMANCE!!!!!");
		this->buf = new char[this->buf_size];
		this->xio_mr = xio_reg_mr(this->buf, this->buf_size);
		if (this->xio_mr == NULL) {
			MSGPOOL_LOG_ERR("registering memory failed with xio_reg_mr(buf=%p, buf_size=%d) (errno=%d '%s')", this->buf, this->buf_size, xio_errno(), xio_strerror(xio_errno()));
			delete[] this->buf;
			throw std::bad_alloc();
		}
	}
	else {
		this->buf = (char*) x_buf->addr;
		this->xio_mr = x_buf->mr;
	}
	BULLSEYE_EXCLUDE_BLOCK_END

	msg_ptrs = new Msg*[msg_num];

	for (int i = 0; i < msg_num; i++) {
		msg = new Msg((char*) buf + i * (in_size + out_size), xio_mr, in_size, out_size, this);
		add_msg_to_pool(msg);
		msg_ptrs[i] = msg;
	}

	MSGPOOL_LOG_DBG("CTOR done. allocated msg pool: num_msgs=%d, in_size=%d, out_size=%d", msg_num, in_size, out_size);
	return;
}

MsgPool::~MsgPool()
{
	Msg* msg = NULL;
	while ((msg = get_msg_from_pool()) != NULL) {
		delete msg;
	}

	BULLSEYE_EXCLUDE_BLOCK_START
	if (this->x_buf) { //memory was allocated using xio_alloc
		if (xio_free(&this->x_buf)) {
			MSGPOOL_LOG_DBG("Error xio_free failed: '%s' (%d)", xio_strerror(xio_errno()), xio_errno());
		}
	}
	else { //memory was allocated using malloc and xio_reg_mr
		if (xio_dereg_mr(&this->xio_mr)) {
			MSGPOOL_LOG_DBG("Error in xio_dereg_mr: '%s' (%d)", xio_strerror(xio_errno()), xio_errno());
		}
		delete[] this->buf;
	}
	BULLSEYE_EXCLUDE_BLOCK_END

	delete[] msg_ptrs;
	MSGPOOL_LOG_DBG("DTOR done");
}

Msg* MsgPool::get_msg_from_pool()
{
	if (msg_list.empty()) {
		return NULL;
	}
	Msg* msg = msg_list.front();
	msg_list.pop_front();
	return msg;
}

void MsgPool::add_msg_to_pool(Msg* msg)
{
	msg_list.push_front(msg);
}
